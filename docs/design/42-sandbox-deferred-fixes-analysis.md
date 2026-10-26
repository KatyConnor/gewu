# 沙箱文件链路三项遗留改进——功能分析与实施预案

| | |
|---|---|
| 文档编号 | 42 |
| 状态 | 待审核（分析报告，未实施） |
| 关联 | 27-agent-sandbox-design、34-sandbox-lifecycle-implementation-plan、本次已完成的路径规范化/code_execute 挂卷/wenshi 落盘缺陷修复 |
| 背景 | 会话读不到历史文件问题（根因 1/2 已修复）遗留三项架构级改进，本文给出可审核判定的改动细节 |

---

## 0. 总览

| # | 改进项 | 解决的问题 | 推荐方案 | 改动面 | 预估工作量 | 建议优先级 |
|---|--------|-----------|----------|--------|-----------|-----------|
| 1 | 跨轮工具调用回放 | 模型每轮重新猜路径、重复探查、丢工具结论（根因 3 放大器） | **B+**：文件清单注入 + 过程时间线回放（无 DDL）；备选 A：完整 tool_calls 持久化回放 | 2-4 文件 | 2~3 人日（B+）/ 5~8 人日（A） | 高（成本低收益直接） |
| 2 | 子代理工作空间隔离 | 子代理文件工具不可用（默认 sessionId=null 直接抛错）；历史共享与工作空间共享耦合在一个开关 | **方案 a**：`workspaceSessionId` 与 `sessionId` 解耦 | 3-4 文件 | 2~3 人日 | 高（当前子代理文件能力完全不可用） |
| 3 | MinIO 与沙箱卷双向同步 | "我的文件"与 AI 工作区互为孤岛，用户上传的文件 AI 读不到、AI 产物不另存就进不了"我的文件" | **方案 B**：事件驱动 + 周期对账的同步服务，分三阶段落地 | 新增 1 服务 + DDL + 2 处钩子 | 8~12 人日（分三阶段可独立验收） | 中（体验补全型，可分期） |

依赖关系：三项相互独立，可并行立项。项 1 的"文件清单注入"素材来自 `session_file_change` 表，项 2 的写入归属复用同一张表，项 3 的 volume→MinIO 触发器也挂在该表事件上——三者共享同一数据源但互不阻塞。

---

## 1. 项一：跨轮工具调用回放

### 1.1 现状（代码证据）

| 事实 | 位置 |
|------|------|
| 会话历史仅持久化 user / assistant / system 三类消息（`session_message` 表，`message_type` 区分），工具调用与结果不落库 | `SessionContextService.appendUserMessage/appendAssistantMessage`（gewu-application/session/SessionContextService.java:223-282） |
| 组装 LLM 上下文时只映射 user→user、其余→assistant 两个角色，`content` 即消息文本 | 同文件 `toLlmMessage`（:330-336） |
| 历史加载以 `task.sessionId` 非空为开关，`buildContextMessages` 一次拉取 + 超过 32000 tokens 触发压缩（保留最近 24 条） | `ReactAgentExecutor`（gewu-agent-engine/.../core/ReactAgentExecutor.java:1797-1798）、`SessionContextService.buildContextMessages`（:57-155） |
| 工具调用过程只以"过程时间线摘要"形式进 assistant 消息的 `metadata.process`（轻量 JSON 条目：thinking 段 / ask_user / 子代理状态等），供前端历史回放渲染，**不回灌 LLM** | `AiChatController`（gewu-interface/.../AiChatController.java:285-330、628-653） |
| 文件写入已有独立台账：`session_file_change`（路径级 before 快照）+ `session_file_change_event`（每次写入的 +N/-N churn，含 turn_seq） | `SessionFileWorkspaceService.recordChange/recordWriteEvent`（:84-111） |

### 1.2 问题机理

工具调用是**回合内**（单次 AgentTask 执行的内存消息列表）存活物，回合结束即丢弃。下一回合模型对上一轮"读过哪个文件、写过什么路径、命令输出结论"没有任何可靠记忆，只能：重新 list_dir 探查（重复延迟）、凭对话文本里的只言片语猜路径（猜错即复现"文件不存在"）、或丢失命令执行的关键结论。

### 1.3 目标与非目标

- **目标**：新回合开始时，模型能可靠知道——本会话写过/改过哪些文件（路径清单）、关键工具调用的参数与结论摘要；压缩发生后该信息仍存活。
- **非目标**：完整还原每一次工具调用的原始输出（成本失控）；跨会话共享；前端过程时间线行为变更（已有，不动）。

### 1.4 方案对比

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|------|------|------|------|------|
| **A. 完整回放**：工具调用/结果按 OpenAI 协议（assistant 消息带 `tool_calls` + `role=tool` 消息）持久化为新 `message_type` 行并回灌 | 协议原生、信息无损 | token 成本随工具轮数线性膨胀，与 32k 压缩阈值互相对冲；需改 `Message` 模型 toolCalls 序列化、压缩器适配、消息表查询过滤，前端消息接口需过滤工具行；风险面大 | 长期正确解，但当前痛点用不到全量 |
| **B. 文件清单注入**（推荐主方案）：每回合把 `session_file_change` 汇总为"本会话文件清单"注入系统提示 | 无 DDL、token 恒定小、天然跨压缩存活 | 只覆盖文件类工具（命令输出等不覆盖） | 与 A 互补 |
| **B+. 时间线摘要回放**（推荐补充）：把已落库的 `metadata.process` 轻量条目（工具名+参数摘要+结论截断）作为 system 消息注入 | 复用已持久化数据、无新表 | 摘要粒度受现有 process 累积逻辑限制（需扩 2-3 个字段） | 与 A 互补 |
| C. 混合：最近 N 轮完整回放 + 更早轮次走 B+ | 折中 | 两套机制都要做，首期复杂度高 | 作为 A 的演进形态保留 |

### 1.5 推荐方案（B+）详细设计

**素材来源（均为已有数据，零新表）**

1. 文件清单：`session_file_change`（deleted=0）按 `updated_at` 倒序取前 20 条，拼 `path（+N/-N，最后修改于第 X 回合）`；写入条数从 `session_file_change_event` 聚合。
2. 工具摘要：assistant 消息 `metadata.process` 数组（现有条目类型：thinking 段、ask_user、subagent_status、工具调用行）。需在 `AiChatController` 的 process 累积处为工具条目补充 2 个字段：`p`（参数摘要，截断 120 字符）、`r`（结果首行/错误，截断 200 字符）——当前条目只有工具名与耗时。

**注入点**：`SessionContextService.buildContextMessages` 返回前，追加一条 system 消息：

```
[工作空间状态] 本会话已写入/修改的文件（path 相对工作空间根，勿带 workspace/ 前缀）：
- fnamefix/internal/decoder/decoder.go（+42/-3，第 3 回合）
- fnamefix/conf/config.yaml（+8/-0，第 3 回合）
[近期工具要点] （可选开关，默认最近 10 条）
- read_file(fnamefix/conf/config.yaml) → 成功
- code_execute(shell: go test ./...) → exit 0, "ok  fnamefix"
```

**改动文件**：`SessionContextService.java`（组装与注入，新增 2 个 `@Value` 开关）、`SessionFileWorkspaceService.java`（暴露只读查询方法 `listChangeSummary(sessionId, limit)`）、`AiChatController.java`（process 工具条目补 p/r 字段）。无 DDL、无接口变更。

**Token 预算**：清单 20 行 ≈ 300-500 tokens；工具摘要 10 条 ≈ 300 tokens；合计 <1k，远低于压缩阈值，且作为 system 消息不受压缩（现有压缩已保留全部 system 消息，:117-123）。

**配置项**：`gewu.session.file-inventory-enabled:true`、`gewu.session.file-inventory-limit:20`、`gewu.session.tool-brief-enabled:true`、`gewu.session.tool-brief-limit:10`。

**验收标准**：① 同会话第二轮，模型不调用 list_dir 即能直接 read 上一轮写入的路径（日志/时间线可证）；② 触发压缩后清单仍在上下文；③ 关闭开关后行为与现状一致；④ 现有单测全绿 + 新增 `SessionContextService` 组装单测。

**方案 A（若审核要求完整回放）要点**：`session_message.message_type` 扩展 `tool_call`/`tool_result` 两值（无 DDL，content 存 JSON：`{toolCallId, name, arguments, output, duration}`）；`buildContextMessages` 按 seq 排序还原 OpenAI 配对结构（assistant.tool_calls → role=tool 逐条）；压缩器把成对工具消息折叠为一行摘要；`getContext`（前端用）过滤这两类；持久化点在 `ReactAgentExecutor` 工具结果处与 doFinally 落库链路。风险：token 膨胀需配独立上限（如工具行合计 ≤6k tokens 超限回退 B+ 形态）；`Message`/各 provider 适配层需验证 toolCalls 字段在非流式与流式两条路径的序列化一致性。工作量 5~8 人日。

---

## 2. 项二：子代理工作空间隔离

### 2.1 现状（代码证据）

| 事实 | 位置 |
|------|------|
| 子代理任务构建：`.sessionId(shareHistory ? ctx.getSessionId() : null)`——sessionId 同时决定"是否加载父会话历史"与"文件工具是否有工作空间"，二者耦合 | `ReactAgentExecutor.buildSubAgentTask`（:1313-1330），配置项 `AgentEngineConfig.Subagents.shareSessionHistory` |
| 文件工具绑定：`resolveBinding → requireSession(ctx.getSessionId())`，sessionId 为空直接抛"会话未绑定工作空间（sessionId 缺失）" | `SessionFileWorkspaceService`（:494-501、529-531） |
| 历史加载同样以 `task.sessionId` 非空为开关 | `ReactAgentExecutor`（:1797-1798） |
| 默认 `shareSessionHistory=false` → 子代理**没有** sessionId → 文件工具完全不可用（写了也写不进、读必报错） | 同上 |

### 2.2 问题机理

派生子代理做并行调研/批量改造是 spawn_subagents 的主用途，而这类任务恰恰最需要文件工具。当前默认配置下子代理对文件无能为力；打开 shareSessionHistory 虽能解锁文件工具，但代价是子代理被灌入父会话全部历史——上下文污染、token 浪费，且与"独立上下文"的 spawn 语义（工具描述明言"子 Agent 看不到当前对话历史"）自相矛盾。

### 2.3 目标矩阵（本项要解耦的正是这两维）

| | 工作空间共享=否 | 工作空间共享=是 |
|---|---|---|
| **历史共享=否**（现状默认） | 完全隔离（文件工具不可用） | ✅ 新增能力：独立上下文 + 共享文件 |
| **历史共享=是** | 理论组合，暂无场景 | 现状 shareSessionHistory=true 的行为 |

### 2.4 推荐方案（方案 a）详细设计

**核心改动：给 AgentTask 增加独立的 `workspaceSessionId`，把"文件工具绑定"从"历史加载"中拆出。**

1. `AgentTask`（gewu-agent-engine/spi/AgentSpec 所在包）新增字段 `workspaceSessionId`（String，可空）。
2. `buildSubAgentTask`：按新配置项分别装配——
   ```java
   .sessionId(shareHistory ? ctx.getSessionId() : null)
   .workspaceSessionId(shareWorkspace ? ctx.getSessionId() : null)
   ```
   新配置 `AgentEngineConfig.Subagents.shareWorkspace`（默认 **true**——与 spawn 工具描述"独立上下文"一致，同时解锁文件能力；历史共享维持默认 false）。
3. `ToolContext` 装配处（executor 内 `buildToolContext`，:1885 附近）：`fileSessionId = task.getWorkspaceSessionId() != null ? task.getWorkspaceSessionId() : task.getSessionId()`，文件工具与回放注入均用 `fileSessionId`；历史加载仍只看 `sessionId`。
4. `SessionFileWorkspaceService` 零改动——它只认 `ctx.getSessionId()`，由装配层保证传入的是父会话 id。

**写入归属与撤销（已验证天然成立）**：回合快照/写入事件按 `ctx.getSessionId()` 记账（`activeTurns`/`turnStates` 键），子代理写入以父会话 id 落账 → 变更列表统一展示在父会话、回合撤销能回滚子代理写入。需在报告中确认一个并发点：子代理与父循环并行写同一文件时 turn 快照按"先到先快照"语义（ConcurrentHashMap.putIfAbsent 风格，`TurnState.beforeByPath`），行为安全但 diff 基线可能与直觉不符——验收项列入。

**可选增强（二期）**：`SubAgentSpec` 增加可选 `workdir` 参数，绑定形式改为 `workspaceSessionId + 子目录前缀`（`SessionFileWorkspaceService.Binding.root` 拼接 `/subagents/{name}`），实现目录级隔离；首期不做。

**改动文件**：`AgentTask`、`AgentEngineConfig`、`ReactAgentExecutor`（buildSubAgentTask + buildToolContext 两处）。无 DDL、无接口变更、`SessionFileWorkspaceService` 零改动。

**风险**：① 子代理注入攻击面——被派生的子代理若读了不可信内容并写入文件，变更会进父会话（与主循环同风险，由既有 SecurityChain/代码扫描覆盖，不新增缓解）；② 配额——子代理文件工具不消耗 LLM token，无配额影响；③ `spawn_subagents` 工具描述需补一句"子代理可读写当前工作空间"，避免模型意外。

**验收标准**：① 默认配置下 spawn 的子代理能 read/write/list 父会话文件（时间线可见）；② 子代理不携带父会话历史（日志确认 buildContextMessages 未以父 sessionId 调用）；③ 子代理写入出现在父会话变更列表且回合撤销可回滚；④ `shareWorkspace=false` 时行为回到现状（报"会话未绑定工作空间"）。

---

## 3. 项三：MinIO 与沙箱卷双向同步

### 3.1 现状（代码证据）

| 维度 | 沙箱卷（AI 工作区） | MinIO（"我的文件"） |
|------|--------------------|--------------------|
| 存储位置 | Docker 命名卷 `gewu-ws-<workspaceId 前 12 位>` → 容器 `/workspace` | bucket `gewu-documents`，objectKey `workspaces/{userId}/{filePath}` |
| 元数据 | 无（纯文件系统）；会话写入另有 `session_file_change` 台账 | MySQL `workspace_file` 表（parentId 树、filePath、objectKey、checksum、version、status 软删） |
| 读写 API | `DevWorkspaceService`（exec `ls -la --time-style=+%s` / cat / tee / rm，:193-242）；会话文件工具走 `ContainerFileService`（docker cp tar 流） | `WorkspaceService` + `MinioStorageService`（putObject/getObject/预签名/按前缀删） |
| 归属关系 | 由 `Workspace` 行持有 devSandboxId | 同一张 `Workspace` 行持有 `storagePath`——**二者天然 1:1（同一 workspaceId）**，无需映射表 |
| 现有桥 | 仅人工"另存"（`FileOutputService` → `createFileFromContent`，落 MinIO `ai-generated/` 目录） | 同左 |

### 3.2 问题机理与目标

- 用户在"我的文件"上传/编辑的内容 AI 会话读不到（文件工具只锚定卷）；
- AI 会话产物不点"另存"就永远不出现在"我的文件"；
- **目标**：同一 workspace 的两侧文件视图最终一致（秒~分钟级），双向可读可写；**非目标**：实时强一致、替换现有文件工具存储后端（那是方案 C）、chat 过程时间线/变更面板行为变更。

### 3.3 方案对比

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|------|------|------|------|------|
| A. 容器内 FUSE 挂 MinIO（s3fs/rclone mount 到 /workspace） | 无同步逻辑，单一视图 | FUSE 在容器内稳定性差、元数据（mtime/大小）不可靠、git 操作（大量小文件 rename）性能崩溃 | 否 |
| **B. 事件驱动 + 周期对账同步服务**（推荐） | 独立 `WorkspaceSyncService`，双向增量复制 + 冲突策略 | 实现清晰、可分期、故障可降级（同步挂掉不影响两侧各自可用） | 最终一致有窗口；需处理冲突/删除传播 | ✅ 分三阶段 |
| C. 存储后端切换：文件工具读写改走 MinIO，卷只做执行缓存 | 彻底消灭双源 | 改动面覆盖文件工具全部链路 + git 性能（MinIO 延迟）、一次性工程 15+ 人日 | 长期方向，本轮不做，作为 B 的演进终态记录 |

### 3.4 推荐方案（B）详细设计

**映射关系（零映射表）**：`workspaceId` ↔ volume `gewu-ws-{id前12}` ↔ MinIO 前缀 `workspaces/{userId}/`，均为现成字段。卷内路径 `P` ↔ `workspace_file.filePath = P`（objectKey = `storagePath + P`）。`workspace_file` 现有 `filePath` 语义即卷内相对路径，直接对齐。

**数据模型（新增 1 表 + 2 列）**：

```sql
-- 同步游标与待办（DDL-1）
CREATE TABLE workspace_sync_state (
  id            VARCHAR(32) PRIMARY KEY,        -- ULID
  workspace_id  VARCHAR(32) NOT NULL,
  file_path     VARCHAR(512) NOT NULL,
  direction     TINYINT NOT NULL,               -- 1=V2M(卷→MinIO) 2=M2V 3=TOMBSTONE
  source_mtime  BIGINT,                          -- 卷侧 mtime（ls --time-style=+%s）
  source_size   BIGINT,
  status        TINYINT NOT NULL DEFAULT 0,     -- 0待办 1成功 2冲突 3失败
  fail_reason   VARCHAR(500),
  created_at    BIGINT, updated_at BIGINT,
  UNIQUE KEY uk_ws_path (workspace_id, file_path)
);
-- workspace_file 加 2 列（DDL-2）
ALTER TABLE workspace_file
  ADD COLUMN volume_mtime BIGINT NULL COMMENT '最近同步时卷侧 mtime',
  ADD COLUMN sync_status  TINYINT NULL COMMENT '0干净 1待同步 2冲突';
```

**组件与流程**：

1. **`WorkspaceSyncService`**（gewu-application/workspace/，新增）：
   - `enqueue(workspaceId, path, direction)`：upsert `workspace_sync_state`（同路径同方向幂等合并）。
   - `syncOne(state)`：按方向执行——V2M：`DevWorkspaceService.readFile` → `MinioStorageService.uploadWorkspaceContent/Bytes` + upsert `workspace_file`（version+1，记录 volume_mtime）；M2V：`MinioStorageService.getContent` → `DevWorkspaceService.writeFile`（二进制走 `SandboxFileController` 的 upload 通道）；TOMBSTONE：对侧删除（软删 workspace_file / 卷内 rm）。
   - `reconcile(workspaceId)`：对账兜底——卷侧 `ls -la` 快照 vs `workspace_file` 全量 diff，差异入队（处理漏事件的最终一致）。
2. **触发器（3 个挂点）**：
   - 卷→MinIO：`SessionFileWorkspaceService.recordWriteEvent` 成功后异步 `enqueue`（已有精确的 path 台账，零额外扫描）；agent 一次性沙箱的写入无台账，由周期对账兜底。
   - MinIO→卷：`WorkspaceService.uploadFile/saveFileContent/deleteFile/createDirectory` 提交后异步 `enqueue`（Spring 事件或直接调用，同事务外）。
   - 周期对账：`@Scheduled(fixedDelay=300s)` 扫"active workspace（最近使用 ≤7 天）"逐个 reconcile，配 `gewu.sync.reconcile-enabled`。
3. **冲突策略（M2）**：同步前比对——卷 mtime/size vs `workspace_file.volume_mtime`：双侧均有新变化 → 默认 **last-writer-wins（mtime 新者胜）+ 冲突副本**（落败方存为 `path.conflict-{ts}`，status=2 记录），不做合并；`workspace_file.version` 正常递增。
4. **删除传播（M3）**：卷侧删除 → TOMBSTONE → MinIO 软删（status=0，保留对象 7 天防误杀，对账发现墓碑超龄再物理删）；反方向同理。防删除风暴：单次对账每 workspace 删除操作 ≤100 条，超出转人工告警（防沙箱卷重置清空 MinIO）。
5. **限制与排除（V2 修订，实施后二次审核确认）**：同步通道准入 = 排除规则 ∧ 文档扩展名白名单——
   ① `projects/**`（Git 克隆区）与任何 `.git` 段硬排除；② **文档扩展名白名单默认拒绝**
   （`gewu.sync.doc-extensions`，默认 md/markdown/txt/pdf/doc(x)/xls(x)/ppt(x)/csv/tsv/rtf/常见图片）——
   **无项目会话写在工作区根的代码（如 `fnamefix/**`）、开发空间直接创建的代码、无扩展名文件一律不入 MinIO**，
   代码由 Git 仓库 push/pull 管理；单文件 ≤100MB（对齐 WorkspaceService.MAX_FILE_SIZE）、单 workspace 待办 >2000 时批量限流。

**改动文件**：新增 `WorkspaceSyncService` + `WorkspaceSyncState` 实体/Mapper + DDL 脚本；钩子改 `SessionFileWorkspaceService`（1 行 enqueue）、`WorkspaceService`（4 处 enqueue）；`application.yml` 配置块。

**分阶段验收**：

| 阶段 | 范围 | 验收 |
|------|------|------|
| M1（3~4 人日） | V2M 单向：AI 会话写入 → "我的文件"可见（含 ai-generated 既有通道并存） | 会话写文件 → 不点另存，"我的文件" 30s 内出现；content 与卷一致；单测覆盖 syncOne |
| M2（3~4 人日） | M2V 反向 + 冲突副本 | 上传文件 → 会话 list_dir/read_file 可读；双侧改同文件产生 conflict 副本 |
| M3（2~4 人日） | 删除传播 + 周期对账 + 限流告警 | 卷删文件 → MinIO 软删；对账补齐漏事件；删除风暴被限流 |

**风险与回滚**：同步失败不影响两侧原功能（status=3 留痕 + 告警）；循环同步风险由"方向位 + source_mtime 比对"消除（同步动作本身不再入队）；回滚 = 关配置开关，钩子变 no-op，DDL 列可空无破坏。

---

## 4. 实施顺序建议

```
项2 子代理工作空间（2~3人日，无DDL，立即解锁子代理文件能力）
  → 项1 B+ 回放注入（2~3人日，依赖项2的 fileSessionId 概念可复用其装配点，但非硬依赖）
    → 项3 M1（3~4人日）→ M2 → M3（项1完成后 file 台账语义稳定，钩子挂点更可靠）
```

三项合计首期（2 + 1 + M1）约 7~10 人日，可在一个迭代内完成；项 3 的 M2/M3 与方案 A（完整回放）、方案 C（存储后端切换）作为二期决策项。

## 5. 审核判定清单（每项给出"做/不做"的判定依据）

| 判定点 | 若"是"则做 | 若"否"则 |
|--------|-----------|----------|
| 项1：是否接受 <1k token/回合的常驻 system 注入成本？ | 做 B+ | 只做项 2；A 方案另行评审 |
| 项1：是否需要命令输出类工具结论跨轮存活（而非仅文件）？ | 需要 → B+ 补 p/r 字段 | 只做文件清单注入（更小） |
| 项2：是否接受子代理默认获得父会话文件读写权限？ | 做（默认 shareWorkspace=true） | 改为默认 false，spawn 工具描述声明由模型显式请求 |
| 项3：是否接受最终一致（秒~分钟窗口）而非实时？ | 做 B 方案 | 评估方案 C（15+ 人日）或维持人工另存 |
| 项3：`.git` 排除、100MB 上限、冲突副本策略是否可接受？ | 按 3.4 执行 | 先定策略再实施 |
