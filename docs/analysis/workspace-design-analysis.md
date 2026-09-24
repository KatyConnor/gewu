# 格物平台智能体工作空间设计分析报告

> **分析对象**：gewu-platform（格物平台）智能体执行的工作空间与文件存储体系
> **分析方法**：三路独立代码探索交叉验证 + 关键文件人工复核（LPU 纪律：无证据不结论，结论均带 `文件:行号` 锚点，核心结论置信度 A 级——代码直接证据）
> **设计文档出处**：`docs/design/39-user-workspace-design.md`（用户工作空间）、`docs/design/40-dev-workspace-design.md`（开发工作空间/双轨模型）、`docs/design/27-agent-sandbox-design.md`（沙箱设计）；设计与实现高度一致
> **日期**：2026-09-16

---

## 一、核心结论（TL;DR）

格物平台的工作空间是**"按用户隔离的双轨存储模型"**，智能体产生的文件**按文件性质分流到四条存储通道**，后端几乎不写本地磁盘：

1. **双轨模型**：每个用户一行 `workspace` 记录，`mode` 字段区分两种形态——
   - `storage` 轨：**MinIO 对象存储**（bucket `gewu-documents`，前缀 `workspaces/{userId}/`），服务文档上传/AI 产物等非开发场景；
   - `dev` 轨：**Docker 命名卷**（卷名 `gewu-ws-{workspaceId前12位}`，挂载容器内 `/workspace`），服务 git clone/编译/运行，**也是智能体内置文件工具的执行后端**。
2. **四条通道**：代码文件 → Docker 卷；AI 产物 → MinIO；过程数据（消息/时间线/变更追踪/Token 账本）→ MySQL；长期记忆/推理轨迹 → pgvector。
3. **隔离轴**：用户级**物理隔离**（一用户一卷）+ 会话级**逻辑路由**（`session.directory` 决定容器内相对根），**没有任务级（per-task）工作目录**——`ToolContext.workspaceRoot/sandboxId` 是声明了但从未赋值的预留字段。
4. **最大结构性缺口**：存储"只增不减"——Docker 卷无删除逻辑、无数据 TTL、回收站语义有 schema 无实现、`deleteWorkspacePath` 存在但全库无调用方。

---

## 二、架构位置：工作空间在系统中的落点

Maven 多模块（Java 21 / Spring Boot 3.2.5）+ Next.js 前端 + Electron 桌面端，三个可部署服务：

```
gewu-web / gewu-desktop（前端：ChatPage / FileEditorPanel / MonacoDiff / WorkspacePage / DevWorkspacePage）
      │ HTTP / SSE
gewu-interface (:8080 主应用：Controller / Security / Flyway V1~V42)
      │
gewu-application（应用层 CQRS）
      ├─ WorkspaceService            ── mode=storage 轨（MinIO）
      ├─ DevWorkspaceService         ── mode=dev 轨（沙箱/git/凭证/文件API）
      ├─ SessionFileWorkspaceService ── FileWorkspaceSpi 实现（路由+变更追踪）
      │      │ HTTP :8082（X-Internal-Api-Key）
      │      ▼
      │   gewu-sandbox（沙箱服务）→ Docker daemon
      │      ├─ DockerSandboxProvider（卷挂载/容器生命周期）
      │      └─ ContainerFileService（docker cp tar 流读写 /workspace）
      └─ AgentExecutionEngine → gewu-agent-engine（进程内 ReAct 引擎）
                └─ read_file / write_file / edit_file / list_dir 四个内置文件工具
                     经 FileWorkspaceSpi（SPI 解耦，引擎不感知沙箱）

存储介质：MySQL/OceanBase（元数据+过程数据）｜MinIO（对象）｜pgvector（向量）｜Docker Volume（工作文件）
```

**一次智能体任务的文件流**：用户在 ChatPage 发消息 → SSE 流式执行 → 引擎 ReAct 循环调用 `write_file` → 经 `FileWorkspaceSpi` → `SessionFileWorkspaceService` 按 `session.directory` 解析根目录与用户 dev 沙箱 → `SandboxClient` HTTP 调沙箱服务 → `ContainerFileService` 用 **docker cp tar 流**写入容器 `/workspace`（持久化在 Docker 卷）→ 首次修改前 before 快照落 `session_file_change` 表 → 前端 `FileEditorPanel/MonacoDiff` 拉取差异（after 不落库、按需从沙箱读）。

多智能体路径：`OrchestrationController` → `OrchestrationService.executeGraphStream` → 引擎内 `OrchestrationEngine`/`GraphNodeExecutor`（角色分工、HITL 审批、预算熔断）。

---

## 三、领域模型：一个 workspace，两种形态

`workspace` 表（V19/V20 迁移，**一用户一行**，唯一键 `uk_workspace_user`）：

| 字段 | 作用 | 证据 |
|---|---|---|
| `userId` / `workspaceName` | 用户归属（唯一） | `gewu-domain/src/main/java/com/gewu/domain/workspace/Workspace.java:13-14` |
| `storagePath` | MinIO 前缀 `workspaces/{userId}/` | `WorkspaceService.java:83` |
| `quotaBytes / usedBytes / fileCount` | 配额记账 | `Workspace.java:16-18` |
| `mode` | **storage=MinIO，dev=Docker卷** | `Workspace.java:20-21` |
| `devSandboxId` | mode=dev 时关联的开发沙箱 | `Workspace.java:22-23` |

配套实体：`workspace_file`（MinIO 文件元数据：object_key/checksum(SHA-256)/version/**status=2 回收站语义**，V19:22-49）、`workspace_project`（git 仓库：repo_url/local_path/clone_status，V20）、`git_credential`（SM4 加密的 SSH key/token，V20）。

**配额体系**：普通用户 1GB、DEV/测试角色 5GB、ADMIN 20GB（`WorkspaceService.determineQuotaByRoles:96-109`），单文件上限 100MB（`:54,185`）。

---

## 四、产生的文件怎么划分：四条存储通道全景

| 文件类型 | 通道 | 路径模式 | 写入方 | 访问方式 | 保留策略 |
|---|---|---|---|---|---|
| **代码/工作文件**（智能体写改的源码、git 仓库） | Docker 卷 | 卷 `gewu-ws-{wid前12位}` → 容器 `/workspace`（项目会话再路由到 `/workspace/projects/{projectId}/repo`） | 引擎文件工具 → `SessionFileWorkspaceService` → `ContainerFileService` docker cp | 前端 `DevWorkspacePage`、`/api/v1/dev-workspaces/files*` | **永不删除**（destroy 只删容器不删卷） |
| **用户上传/AI 产物**（报告 md/docx、代码输出） | MinIO | `workspaces/{userId}/{filePath}`（含 `ai-generated/`）；带需求上下文时 `projects/{projectId}/requirements/{requirementId}/{category}/{fileName}` | `FileOutputService.saveArtifact:122-175`（ContentClassifier 从推理输出拆出 code/markdown/json/yaml/csv/docx） | 预签名 GET URL（7 天有效）；文件卡片经消息内嵌 `<!--FILES:[...]-->` 恢复（`AiChatController.java:369-372`） | 配额内硬删（`deleteFile`），**回收站未实现** |
| **过程数据**（消息/时间线/变更快照/执行账本/Token 记账/编排快照） | MySQL | `session_message.content/metadata`（时间线 JSON `{"process":[...]}`，上限 100 条、thinking 截 500 字、tool 参数/结果截 200 字）、`session_file_change.before_snapshot`、`agent_execution`、`orchestration_execution.graph_snapshot` | `AiChatController:354-395` 流结束后统一落库；`CostAccountingService.record` 原子累计 token 到 `session` 行 | 会话历史 API；前端解析 `metadata.process` 还原时间线 | **无 TTL/清理** |
| **长期记忆/推理轨迹**（wenshi 认知子系统） | pgvector | `wenshi_semantic_fragment / episodic_event / procedural_memory / experience / user_profile / reasoning_trace`（HNSW 索引，`db/wenshi/V8__wenshi_knowledge_layer.sql`） | `EpisodicMemoryService` 等 | 认知路由（`gewu.wenshi.routing.stream`） | 认知整理 cron（非删除型） |

此外：审计日志（沙箱文件访问）落 `sandbox_audit_log`（**SM3 哈希链防篡改**，`SandboxAuditService.logFileAccess:52-54`）；服务运行日志仅在部署脚本层落到仓库根 `logs/`（源码内无 logback FileAppender）。

**关键点：后端没有 `Files.write` 类本地文件系统写入**——一切文件操作要么 docker cp 进卷、要么进 MinIO、要么进数据库。没有 `tasks/`、`sessions/`、`artifacts/` 之类宿主机目录概念，也没有 bind mount 宿主目录。

---

## 五、双轨存储的目录树全景

### 5.1 storage 轨（MinIO，bucket `gewu-documents`，`gewu-interface/src/main/resources/application.yml:299`）

```
gewu-documents/
└── workspaces/{userId}/
    ├── src/  docs/  uploads/      ← 注册时建的默认目录（仅 DB 元数据，非物理预建，WorkspaceService.java:115）
    ├── ai-generated/              ← AI 生成文件，惰性创建（WorkspaceService.java:333-357）
    │   └── ai_{fileType}_{uuid8}.md/.docx/...（无建议名时的命名规则，FileOutputService:180-215）
    └── {用户/AI 写入的其他文件}

projects/{projectId}/requirements/{requirementId}/{category}/{fileName}   ← 需求上下文产物（category=docs/tests/reports）
projects/{projectId}/{phaseCode}/{uuid8}_{fileName}                       ← 项目阶段文档（MinioStorageService:50-67）
```

### 5.2 dev 轨（Docker 卷 → 容器 `/workspace`）

```
Docker Volume: gewu-ws-{workspaceId[0:12]}   （rw 挂载，为此关闭容器只读根文件系统）
└── /workspace/
    ├── projects/
    │   ├── {projectId}/repo/        ← 业务"项目"仓库（ProjectRepoService.java:20-41 统一路径）
    │   └── {projectName}/           ← 工作空间 git 项目（DevWorkspaceService.java:245，local_path=projects/{name}）
    │       ├── .git/  src/  pom.xml / package.json ...
    └── (无项目会话的文件直接落在根 = 用户默认空间根)

凭证注入（不在卷内，防持久化泄漏）：/root/.ssh/id_rsa、/root/.git-credentials（SM4 解密后注入，DevWorkspaceService.java:398-421）
```

**会话级路由规则**（`gewu-application/.../session/SessionFileWorkspaceService.java:253-272`）：`session.directory` 存容器内路径——项目会话 = `/workspace/projects/{projectId}/repo`（`SessionService.java:69-83` bindWorkspace），无项目会话为空（= 工作空间根）。运行时把该 directory 剥掉 `/workspace` 前缀转成**卷内相对根**，文件工具的相对路径拼在其后，`..` 越界一律拒绝（`:44,312-328`）。

---

## 六、核心机制：智能体文件操作的执行链路

1. **引擎侧 SPI 解耦**：`ReactAgentExecutor` 注册 `read_file/write_file/edit_file/list_dir` 四个内置工具（`gewu-agent-engine/.../ReactAgentExecutor.java:941-955`），`executeFileTool`（`:638-705`）只调 `FileWorkspaceSpi` 接口（`tool/FileWorkspaceSpi.java:4-7`）——**引擎不知道沙箱存在**，SPI 缺省为 NoOp（`AgentEngineAutoConfiguration.java:330-335`）。
2. **应用层路由**（`SessionFileWorkspaceService.resolveBindingOf:253-257`）：
   - **根目录** = `session.directory` 相对化（项目会话→项目仓库目录；无项目→工作空间根）；
   - **沙箱** = `ensureDevSandboxForUser(userId)`（`DevWorkspaceService.java:112-168`）——**惰性创建 + 探活恢复 + 容器被外部删除后自愈重建**，注释明确"不能因 dev_sandbox_id 非空而直取——该 id 可能已指向被清理的容器"（`SessionFileWorkspaceService.java:274-280`）。
3. **沙箱文件通道**：`SandboxClient` HTTP(:8082) → `SandboxFileController` → `ContainerFileService` 用 **docker cp tar 流**读写（`gewu-sandbox/.../service/ContainerFileService.java:39-70,160-175`）——不经 shell、天然防命令注入，写时自动 `mkdir -p` 父目录。
4. **变更追踪（diff 基线）**：写文件首次修改某路径前记 before 快照，upsert 到 `session_file_change`（唯一键 `session_id+file_path`，V39；`recordChange:154-181`）；再次修改只刷新时间，**before 基线保持首次修改前状态**；CREATE 后清空退化为 DELETE 标记（`:176-178`）。**after 不落库**，diff 在读取时用 LCS 现算（`:184-211`）——避免双份存储。

---

## 七、生命周期管理

| 阶段 | 行为 | 证据 |
|---|---|---|
| 创建（storage） | 用户注册即初始化 | `AuthService.java:129` |
| 创建（dev） | 惰性创建 + 自愈（见上） | `DevWorkspaceService.java:112-168` |
| 挂载 | 创建容器时 workspaceId 非空 → `ensureVolumeExists`（幂等）+ Bind(volume, /workspace, rw) | `DockerSandboxProvider.java:82-93,229-238` |
| 空闲回收 | 每 5 分钟扫描：空闲 30 分钟自动 stop（dev 档 120 分钟） | `SandboxScheduler.java:35-63`；`gewu-sandbox/.../application.yml:63-76` |
| 强制销毁 | agent 专用沙箱（source=agent, auto_destroy）超 300 秒强制销毁（每分钟扫描）；manual 沙箱 TTL 7 天 | `SandboxScheduler.java:93-119`；`SandboxService.java:35-42` |
| **删除** | **不存在**：全库无卷删除调用；`stopDevSandbox` 注释明确"保留 Volume" | `DevWorkspaceService.java:181-187` |

**沙箱生命周期 ≠ 文件生命周期**：容器停了卷还在，文件跨会话持久。一次性 agent 代码执行（`executeCode`）用临时沙箱、用完即毁、不挂工作空间（`SandboxService.java:165-210`）。

---

## 八、隔离与安全设计

| 维度 | 机制 |
|---|---|
| 用户级（主隔离轴） | 一用户一 workspace、一 dev 沙箱、一 Docker 卷（卷名由 workspaceId 派生）；MinIO 前缀按 userId 划分 |
| 会话级 | `session.directory` 目录内路由（**逻辑隔离，非物理**）：同用户多会话共享同一卷，无项目会话全部落在卷根 |
| 任务级 | **无**（`ToolContext.workspaceRoot/sandboxId` 声明后全库无赋值点，`gewu-agent-engine/.../tool/ToolContext.java:37-40`） |
| 沙箱安全基线 | 无工作空间的普通/agent 沙箱：`readonlyRootfs + capDrop(ALL) + tmpfs /tmp 64MB + network none`（`DockerSandboxProvider.java:73-80`）；**dev 沙箱主动放松**：关只读根、开 bridge 网络、换宽松的 DevCommandValidator（`:90-92,156-161`） |
| 文件通道安全 | docker cp tar 流绕过 shell（无注入面）；路径净化拒绝 `..`（`sanitizeRelative:312-328`） |
| 凭证安全 | Git 凭证 SM4 加密存储，解密后注入容器 `/root/`（不落卷，避免持久化泄漏）；工作空间操作全量审计（SM3 哈希链） |

---

## 九、配置体系

| 配置 | 值 | 位置 |
|---|---|---|
| 容器内工作空间根 | `/workspace`（**硬编码常量**，非配置项；两处重复定义） | `ContainerFileService.java:30`、`SessionFileWorkspaceService.java:43` |
| 卷名规则 | `gewu-ws-{workspaceId前12位}`（代码规则） | `DockerSandboxProvider.java:84` |
| MinIO | endpoint localhost:9000 / bucket `gewu-documents` | `gewu-interface/.../application.yml:294-299` |
| dev 沙箱资源 | cpu 2 / memory 4096MB / disk 20480MB | `DevWorkspaceService.java:60-67` |
| dev 镜像 | `GEWU_DEV_IMAGE`：**interface 侧默认 `alpine:latest`，sandbox 侧默认 `gewu/dev-base:latest`（两处默认不一致）** | `gewu-interface/.../application.yml:227` vs `gewu-sandbox/.../application.yml:70` |
| 沙箱生命周期 | idle 30min / manual-ttl 7d / agent-max-lifetime 300s；dev 档 idle 120min、TTL 30d | `gewu-sandbox/.../application.yml:63-76` |

注意：**没有 `WORKSPACE_ROOT` 之类的环境变量或宿主机路径配置**——工作空间根是容器内常量，宿主侧完全由 Docker named volume 承载。

---

## 十、设计评价

### 优点

1. **SPI 解耦干净**：引擎只依赖 `FileWorkspaceSpi` 四方法接口，不感知沙箱/存储实现，可整体替换存储后端而不动引擎。
2. **双轨模型精准**：文档场景（MinIO：便宜、可预签名、天然配额）与开发场景（真实文件系统：.git/inotify/进程）需求本质不同，`docs/design/40` 的论证与实现完全对应。
3. **docker cp tar 流**做文件通道：绕开 shell 注入面，且与命令执行通道（CommandValidator）分离。
4. **before 快照 + 按需 diff**：只存基线不存 after，省一半存储，diff 语义正确（首次修改前状态）。
5. **惰性创建 + 自愈**：`ensureDevSandboxForUser` 处理 DB 状态滞后与容器被外部删除两类故障，健壮性好。
6. **设计文档与实现一致**（39/40/27），可追溯性好。

### 问题与风险

1. **存储只增不减（最严重）**：Docker 卷无删除 API（用户注销/工作空间删除无清理路径）；`MinioStorageService.deleteWorkspacePath:178-192` 写好了但全库无调用方；`workspace_file.status=2` 回收站语义有 schema 无实现；session_message/session_file_change/agent_execution/审计日志均无 TTL；MinIO 无 ILM 生命周期规则。长期运行必然容量失控。
2. **任务级工作空间缺位**：`ToolContext.workspaceRoot/sandboxId` 是死字段；同用户多会话共享一个卷，无项目会话全落卷根——多会话并发写同名文件会互相覆盖，变更追踪按 `session_id+file_path` 唯一键隔离记录，但跨会话 diff 的 before 基线语义会漂移（第二个会话的 before 是第一个会话改后的状态）。
3. **dev 沙箱安全基线放松**：bridge 网络 + 宽松命令校验 + 关闭只读根，一用户一常驻沙箱是横向渗透面；且 `listDir` 走 `exec ls -1` + shell 拼接（`SessionFileWorkspaceService.java:73-74`，有 quoteShell 处理但与 docker cp 通道的安全模型不一致）；`quoteShell` 是删除单引号而非转义（`:336-339`），路径含 `'` 时静默变形。
4. **会话删除无级联**：`SessionService.deleteSession:222-233` 仅逻辑删 session 行，消息/变更记录成为孤儿（schema 无外键）。
5. **配置不一致**：dev 镜像两处默认值不同（alpine vs dev-base）；设计文档写 dev TTL 30 天，实现传 timeout=86400（24h，`DevWorkspaceService.java:156`）。
6. **常量重复定义**：`/workspace` 在 sandbox 与 application 两个模块各硬编码一份，跨模块隐式契约。
7. **预留未实现**：`part`/`session_input`/`session_context_epoch` 表已建但无 Mapper（OpenCode 风格分片持久化未落地）；`before_snapshot` 用 MEDIUMTEXT 全量入库，大文件快照会撑大 DB。

### 改进建议（按优先级）

1. 补**工作空间生命周期闭环**：删除工作空间时级联清卷（`removeVolume`）+ 接线 `deleteWorkspacePath` + 实现回收站软删（schema 已就绪）+ 会话/消息/变更记录 TTL 归档。
2. **落实任务级（或至少确认会话级）路由**：给 `ToolContext.workspaceRoot` 赋值，为无项目会话分配 `/workspace/sessions/{sessionId}/` 子目录，消除多会话覆盖与跨会话 before 基线漂移。
3. dev 沙箱网络收口：git host 白名单/出网代理，替代裸 bridge。
4. `listDir` 改走 docker cp 通道（tar 列目录），统一文件通道安全模型；`quoteShell` 改转义。
5. 统一 dev 镜像默认值与 TTL 配置，`/workspace` 常量下沉到 gewu-common 单点定义。

---

## 十一、证据与置信度

- **A 级（代码直接证据）**：本文全部路径/机制结论，锚点已随文标注；其中最核心的 `Workspace` 实体、`SessionFileWorkspaceService` 路由与变更追踪、设计文档 40 的双轨模型均经逐行复核。
- **B 级（≥2 独立来源推断）**：dev TTL 30 天 vs timeout 86400 的出入（设计文档/配置/代码三方不完全一致）。
- **未找到（明确不存在）**：任务级目录、卷删除逻辑、数据 TTL 清理任务、截图产物、MinIO ILM、宿主机 bind mount 工作空间、`WORKSPACE_ROOT` 环境变量。
