# 格物平台沙箱安全、生命周期与文件存储全景分析报告

> **分析对象**：gewu-platform（格物平台）沙箱服务的安全设计、生命周期管理，以及上传/会话/任务/开发各场景文件存储全景
> **分析方法**：三路独立代码探索交叉验证 + 关键文件人工复核（LPU 纪律：无证据不结论，结论均带 `文件:行号` 锚点；核心文件经本人逐行复核）
> **关联文档**：`docs/design/27-agent-sandbox-design.md`（沙箱设计 V1.2）、`docs/design/34-sandbox-lifecycle-implementation-plan.md`（生命周期落地计划）、`docs/design/39/40`（工作空间设计）；前序分析见 `docs/analysis/workspace-design-analysis.md`
> **日期**：2026-09-21

---

## 一、核心结论（TL;DR）

1. **沙箱安全是"五层防御 + 分级放松"设计**：容器加固基线（只读根/capDrop ALL/no-new-privileges/tmpfs/无网络）→ 双命令验证器按来源路由（严格黑名单 vs 宽松仅禁破坏性命令）→ docker cp 文件通道（绕 shell）→ 服务间内部密钥 → 审计。**dev 沙箱是设计内的一档安全放松**（需真实文件系统+网络+宽松命令）。
2. **生命周期 = 6 状态 + 5 来源分档 + 3 个定时任务 + 自愈**：空闲 30 分钟自动 stop、manual 沙箱 7 天过期标记、agent 沙箱 300 秒强制销毁、**卷永不删除**；dev 沙箱惰性创建并带探活自愈。
3. **文件存储三种落点，回答"沙箱中还是本地还是文件服务"**：
   - **文件服务（MinIO）**：用户上传、AI 产物、项目阶段文档、需求文档；
   - **沙箱（Docker 命名卷，即宿主机 Docker 卷）**：开发文件、会话文件工具产物、项目 git 仓库；
   - **数据库（MySQL/pgvector）**：会话消息/时间线/变更快照、执行账本、编排快照、长期记忆。
   - **后端本地磁盘零业务写入**（全源码无 `Files.write`/`createTempFile` 等，仅部署日志 `logs/`）；`executeCode` 产物不落任何地方（容器销毁即消失）。
4. **重要发现（本次新识别）**：多处安全声明**未接线**——SM3 审计哈希链、镜像白名单、方法级鉴权、文件访问审计均为死代码/死配置；`SandboxProxyController` 存在源码损坏（5 处路径映射字面量为 `/glm-5.3_common`）；`DevWorkspaceService` 存在路径未净化的 shell 注入点。

---

## 二、沙箱安全设计

### 2.1 总体分层

| 层 | 机制 | 状态 |
|---|---|---|
| 运行时隔离 | Docker 容器 + 加固 HostConfig（唯一 provider；设计规划 Firecracker/gVisor **未落地**，`gewu-sandbox/.../runtime/{docker,firecracker,gvisor}` 三个目录为空） | ✅ Docker 单档 |
| 命令层 | 双验证器按 `sandbox.source` 路由 | ✅ |
| 文件层 | docker cp tar 流 + 路径校验（拒 `..`/绝对路径） | ✅ |
| 服务层 | X-Internal-Api-Key + Spring Security denyAll 默认 | ✅（有缺口） |
| 审计层 | `sandbox_audit_log` | ⚠️ 部分（见 2.6） |

### 2.2 容器加固基线（`gewu-sandbox/.../provider/DockerSandboxProvider.java:73-99`，本人逐行复核）

| 配置项 | 值 | 备注 |
|---|---|---|
| readonlyRootfs | `true`（无卷）；**挂工作空间卷时 `false`**（/workspace 需写，:91） | dev/workspace 档放松 |
| capDrop | `Capability.ALL`（:78） | 两档一致 |
| securityOpts | `no-new-privileges:true`（:77，仅此一项） | 未自定义 seccomp/apparmor（Docker 默认） |
| tmpfs | `/tmp: rw,noexec,nosuid,size=64m`（:80） | 设计还规划了 `/var/run`，未做 |
| network | `none`（默认 network-enabled=false）/ `bridge`（dev=true） | 设计规划的独立 IP/VLAN/eBPF/DNS 白名单未实现 |
| memory / cpu | 默认 512MB/1 核（:41-45）；dev 档 4096MB/2 核 | nanoCpus/memory 直设 |
| 磁盘（disk-mb） | **仅存 DB 字段，无 storage-opt/卷配额**（:47-48,61,114） | ⚠️ 未生效 |
| pids limit / ulimits | **未设置** | ⚠️ fork 防护缺位 |
| 运行用户 | 未设置 withUser → 镜像默认（dev-base 无 USER 指令 → **root**） | ⚠️ |
| CMD 覆盖 | `tail -f /dev/null`（:95-99，防基础镜像退出） | |
| 容器名/卷 | `gewu-sandbox-{ULID8}`；workspaceId 非空 → 挂命名卷 `gewu-ws-{id前12位}` rw（:83-93） | 卷创建幂等（:229-238，异常吞掉） |

### 2.3 命令验证器：双轨制（执行时路由，创建时不校验）

**路由规则**（`DockerSandboxProvider.java:156-161`）：`source=="dev"` → DevCommandValidator；其余（manual/agent/project/workspace）→ CommandValidator。

**CommandValidator（严格版，`gewu-sandbox/.../security/CommandValidator.java:18-69`，本人逐行复核）**：
- 禁止字符（正则任一命中即拒）：`;` `|` `&` `$` `` ` `` `(` `)` `{` `}` `<` `>` `\`
- 禁止操作符：`||` `&&` `|>` `<<` `>>` `$(` `${` `` ` ``
- 禁止命令（trim+小写后 `startsWith(cmd+" ")` 或 equals）：`rm` `mkfs` `dd` `shutdown` `reboot` `halt` `poweroff` `init` `kill` `pkill` `killall` `su` `sudo`
- 拒绝换行符、空命令
- **已知绕过面**：前缀匹配可被 `xargs rm`、`/bin/rm`、`sh rm` 绕过（:59 只查开头）

**DevCommandValidator（宽松版，`security/DevCommandValidator.java:19-35`，本人逐行复核）**：仅小写子串匹配 11 个模式，**其余全部放行**（含管道、重定向、变量、命令替换——git/maven/npm 需要）：
`rm -rf /`、`rm -rf /*`、`mkfs`、`dd if=/dev/`、`shutdown`、`reboot`、`halt`、`poweroff`、`init 0`、`init 6`、`:(){ :|:& };:`（fork bomb）。注释明确"仅用于 source=dev，Agent 沙箱仍使用 CommandValidator"。

### 2.4 文件通道安全

- `ContainerFileService.validatePath`（:173-183）：拒绝 null/空、含 `..`（防穿越）、以 `/` 开头（强制相对 `/workspace`）；write/read/upload/delete 四入口均先校验（:40,:80,:113,:151）。
- 读写用 **docker cp tar 流**，不经 shell（天然无注入面），写时自动 `mkdir -p` 父目录。
- 内部受控路径的 `deleteFile`/`ensureContainerDir` 直接 `exec rm -f`/`mkdir -p` 绕过验证器（:146-169,:186-201，注释说明为内部拼接路径）。
- 会话文件工具侧另有 `sanitizeRelative`（去首尾斜杠+拒 `..`+UNSAFE_PATH 正则）与 `quoteShell`（单引号包裹并**删除**内部单引号，非转义）（`gewu-application/.../session/SessionFileWorkspaceService.java:52,558-586`）。

### 2.5 服务间安全（interface:8080 → sandbox:8082）

- **InternalApiKeyFilter**（`gewu-sandbox/.../config/SandboxSecurityConfig.java:88-104`）：`OncePerRequestFilter`，读 `X-Internal-Api-Key`，`MessageDigest.isEqual` **常量时间比较**，通过后注入 `internal-service` 认证（空权限）。
- 端点策略：`/api/**` → authenticated；`anyRequest` → **denyAll**；仅 swagger 豁免；CSRF off、STATELESS（:54-64）。
- 密钥：两侧同一环境变量 `GEWU_INTERNAL_API_KEY`，**弱默认值入仓**（`gewu-dev-internal-key-change-me`）。
- **`@PreAuthorize("hasAuthority('sandbox:manage')")` 是死注解**（`SandboxController.java:69,84`）：gewu-sandbox 无 `@EnableMethodSecurity`（本人 grep 验证：仅 admin-server 与 interface 有），且 filter 授予空权限——鉴权实际只靠 API-Key。
- 部署面：网关路由 `/api/v1/sandboxes/**` 直通 8082 但**不注入**内部密钥；helm 中 sandbox 为 ClusterIP；**docker.sock 以 hostPath 挂载**（`deploy/helm/gewu-platform/templates/sandbox.yaml`）；`deploy/k8s/network-policy.yaml:7-9` 的 podSelector 用 `app:` 标签，与 sandbox pod 的 `app.kubernetes.io/name:` 标签**不匹配——沙箱 pod 不受 ingress 网络策略限制**。

### 2.6 审计：生效与未生效

**生效**（`gewu-sandbox/.../service/SandboxService.logAudit:277-295`，同步写库、try-catch 吞异常）：

| action | 触发点 | 行号 |
|---|---|---|
| CREATE / START / STOP / DESTROY | 生命周期各操作 | :88,:105,:117,:128 |
| COMMAND | **每次 exec 前记录，details=完整命令明文** | :150 |
| EXECUTE_CODE | 一次性代码执行 | :195 |
| RENEW | 续期 | :269 |
| DESTROY（项目批量） | destroyProjectSandboxes | :245 |

局限：`result` 恒为 `"SUCCESS"`（失败不记）、`userId` 无上下文时兜底 `"system"`、`ip_address` 从未赋值。

**未生效（死代码/死配置，含本人 grep 验证）**：
- `SandboxAuditService` 的全部 log 方法（含 `logFileAccess:52-54`）**全项目无调用方**（仅查询侧被 SandboxAuditController 引用）；`@Async` 因无 `@EnableAsync` 也无效。
- **文件访问（FILE_ACCESS）从未记录**——SandboxFileController 的 write/read/upload/download/delete 完全不写审计。
- **SM3 哈希链未实现**：`log_hash` 列（V1 DDL:617）与 `logHash` 字段（`SandboxAuditLog.java:20`）存在，但全库无任何 `setLogHash` 调用、无前序 hash 级联逻辑。
- `SandboxValidator`（镜像白名单 `ALLOWED_IMAGE_PREFIXES`、命令黑名单、资源上限校验）**无任何调用方**——**镜像白名单与资源上限校验实际未生效**，`create` 直接使用调用方传入的任意 image/cpu/memory（`DockerSandboxProvider.java:58-63`）。
- `SandboxSecurityPolicy`（config/）同样未被使用。

### 2.7 镜像体系

- 默认镜像**两处不一致**：sandbox 侧 `gewu/sandbox-base:latest`（`gewu-sandbox/.../application.yml:56`）；interface 侧 `gewu/sandbox:latest`（`application.yml:257`）。且 `sandbox-base` 的 Dockerfile 在全仓不存在。
- dev 镜像：`gewu/dev-base:latest`（基于 eclipse-temurin:21-jdk-jammy，预装 git/openssh/vim/curl/wget/maven/gradle/python3/node20，`docker/Dockerfile.dev`）——但 interface 侧 `GEWU_DEV_IMAGE` 默认 `alpine:latest`（DevWorkspaceService 运行在 interface 进程，**实际生效默认是 alpine**）。
- 拉取：inspect 未命中即 pull，120s 超时（:212-227），**无签名/摘要校验**。
- 模板解析：`SandboxTemplateMatcher`（python:3.12/node:20/openjdk:21-slim/alpine:3.20，:25-29）。

### 2.8 安全缺口清单（按严重度）

| # | 缺口 | 证据 | 级别 |
|---|---|---|---|
| 1 | **DevWorkspaceService 路径未净化拼 shell**：listFiles/readFile/deleteFile 把用户 path 直接拼进命令（如 `"rm -rf /workspace/" + path`、`"cat /workspace/" + filePath`），宽松 DevCommandValidator 下含 `;`/反引号的 path 可注入容器内 root 命令 | `DevWorkspaceService.java:195,204,229` | P0 |
| 2 | **exec 超时不杀进程**：awaitCompletion 超时返回后无 kill，命令在容器内继续运行；且此时拿不到退出码→**默认置 0**，调用方无法区分超时与成功 | `DockerSandboxProvider.java:179-197` | P0 |
| 3 | **SandboxProxyController 源码损坏**：5 处路径映射字面量为 `"/glm-5.3_common"`（应为 `"/{id}"` 系列），按 id 的代理端点（查详情/start/stop/destroy/续期）全部失效 | `gewu-interface/.../interfaceapi/controller/SandboxProxyController.java:53,59,65,71,82`（本人 grep 验证） | P0（功能失效） |
| 4 | 弱默认密钥入仓且两侧一致（`gewu-dev-internal-key-change-me`）；`gewu-sandbox/application.yml:78` 另有硬编码 crypto 密钥 | application.yml:48 / interface:260 | P1 |
| 5 | 镜像白名单+资源上限校验未接线（SandboxValidator 死代码）——任意 image 传入即创建并自动拉取 | 见 2.6 | P1 |
| 6 | 审计缺口：文件访问不记、失败不记（result 恒 SUCCESS）、无 SM3 哈希链防篡改、命令/token 明文无脱敏（git token 明文写 `.git-credentials`，`DevWorkspaceService.java:408-409`） | 见 2.6 | P1 |
| 7 | k8s NetworkPolicy 选择器不匹配——沙箱 pod 不受 ingress 限制；docker.sock 全权挂载（容器逃逸即宿主 root） | `deploy/k8s/network-policy.yaml:7-9` vs helm 标签 | P1 |
| 8 | dev-base 以 root 运行（无 USER 指令）；无 pids limit（fork 耗尽内存/cpu 配额内的进程数）；/workspace 卷无磁盘配额（disk-mb 仅 DB 字段） | Dockerfile.dev / 2.2 节 | P1~P2 |
| 9 | 上传校验薄：沙箱文件上传无类型/MIME/内容校验（`SandboxFileController.uploadFile:61-72` 直接打 tar 进容器）；interface 无 multipart 显式大小限制；MinIO 侧仅扩展名黑名单（.exe/.bat/.cmd/.sh/.ps1/.dll/.so/.dylib，`WorkspaceService.java:55-56`） | | P2 |
| 10 | CommandValidator 前缀匹配绕过面（`xargs rm`/`/bin/rm`） | `CommandValidator.java:59` | P2 |

---

## 三、沙箱生命周期管理

### 3.1 状态机（`gewu-common/.../enums/SandboxStatus.java:5-10`）

枚举 code（小写）：`creating` / `running` / `stopped` / `destroyed` / `error` / `expired`（V2 DDL 注释为大写且默认值 `'CREATED'`，与枚举不一致，实际以代码写入为准）。

全部状态迁移点（全模块仅 6 处写 status）：

| 迁移 | 位置 | 触发 |
|---|---|---|
| →creating | `DockerSandboxProvider.java:110` | create 成功后 |
| →running | `:132` | start（**304 NotModified 幂等视为成功** :127-131，处理 DB 滞后/并发重复 start） |
| →stopped | `:139` | stop（docker stop 10s） |
| →destroyed | `:150` | destroy（rm --force 抛异常仍标 destroyed :145-149） |
| →expired | `SandboxScheduler.java:84` | 每小时过期扫描 |
| expired→stopped | `SandboxService.java:264-265` | renewExpire 续期回置 |

```
creating ──start──> running ──stop / 空闲30min / 过期stop──> stopped ──start──> running
                          │                                     │
                          ├──expireAt 到期──> expired（每小时标记；续期可回 stopped）
                          └──destroy / agent超300s──> destroyed（终态）
任意状态 ──destroy──> destroyed
```

- **无显式状态机约束**（无非法迁移校验）；仅两处软守卫：start/exec 对 `expired` 抛 `SANDBOX_EXPIRED`（`SandboxService.java:96-98,143-145`）。
- **ERROR 是死枚举**：全库无 `setStatus(ERROR)`。
- **容器意外退出（exited）无巡检**：无 inspectContainer 周期检查；仅 interface 侧探活 exec（见 3.5）能发现。

### 3.2 来源分档（source = 5 档，设计文档只规划 3 档）

| source | TTL（expireAt） | 网络 | autoDestroy | 资源档 | 创建入口 |
|---|---|---|---|---|---|
| `manual`（默认） | now+7 天（`lifecycle.manual-ttl-days:7`，显式传 expireAt 优先） | 缺省 false | 0 | 1C/512M/1G 默认 | `POST /api/v1/sandboxes`（前端手动创建，`SandboxController.java:25-31`） |
| `agent` | **不设 expireAt** | false | **1** | 按语言模板：PYTHON 1C/2G/10G、NODEJS 1C/2G/10G、JAVA 2C/4G/20G、SHELL 1C/512M/5G（`SandboxTemplateMatcher.java:26-29`） | `POST /execute` ← executeCode（`SandboxController.java:82-88` ← agent-engine `ToolExecutor.java:226` → `DbSandboxExecutorAdapter`） |
| `project` | projectTtlDays=0 → **永不过期** | false | 0 | 请求模板或默认 SHELL | `POST /project/{projectId}`（`SandboxService.createForProject:213-232`；techStack 推断 `resolveFromTechStack` 是死代码） |
| `dev` | 不设 expireAt；timeout=86400s（是执行超时非 TTL） | **true** | 0 | dev-base 2C/4096M/20480M | interface `POST /api/v1/dev-workspaces/sandbox/start` → `ensureDevSandboxForUser`；会话文件工具惰性触发 |
| `workspace` | 不设 expireAt | false | 0 | shell 模板 | `WorkspaceController.java:106` → `createWorkspaceSandbox:479-491`（挂卷） |

资源上限约束：`CreateSandboxCommand.java:14-30`（cpu 1-16、mem 128-32768MB、disk 256-102400MB、timeout 60-86400s）——注意这是 DTO 校验，SandboxValidator 的独立上限校验未接线（见 2.6）。

### 3.3 完整调用链与四操作语义

**调用链**：前端 → interface `SandboxProxyController`（`/api/v1/sandboxes` 代理，续期兼容 expireSeconds/expireAt/ttlDays 三格式 :82-93）→ `SandboxClient`（HTTP + X-Internal-Api-Key）→ sandbox `SandboxController` → `SandboxService` → `SandboxProviderFactory.getDefaultProvider()`（**恒返回 docker**，唯一实现）→ `DockerSandboxProvider` → docker.sock。

**create**（`SandboxService.createSandbox:44-91`）：模板/镜像解析 → provider.create（拉镜像 120s → CMD 覆盖 `tail -f /dev/null` → 加固配置 → workspaceId 非空挂卷并关只读根）→ insert（creating）→ setLastUsedAt → provider.start（running+startedAt）→ 审计 CREATE。

| 操作 | 做什么 | 删什么 | 留什么 |
|---|---|---|---|
| start | docker start（304 幂等）；刷新 startedAt/lastUsedAt | — | 容器+卷原样，秒级恢复 |
| stop | docker stop（10s）→ stopped | 运行态 | 容器、卷、DB 行 |
| destroy | docker rm --force → destroyed（DB 行 updateById 保留，**不 deleteById**） | 容器实例 | **卷、DB 行、审计记录**（卷全模块无 removeVolume） |
| 过期标记 | running 先 stop → 置 expired；**之后无任何后续动作**（不销毁不清理，每小时幂等重标）；出路=手工 destroy 或 renewExpire 回 stopped | — | 全部 |

### 3.4 三个定时任务（`SandboxScheduler.java`，本人全文复核）

| 任务 | 周期 | 扫描条件 | 动作 |
|---|---|---|---|
| `autoStopIdleSandboxes`（:35-63） | fixedRate 5min | status=running **且 source≠agent** 且 lastUsedAt < now-30min（`idle-timeout-minutes`；`auto-stop-enabled` 总开关） | provider.stop + stoppedAt + update |
| `checkExpiredSandboxes`（:65-91） | fixedRate 1h | expireAt 非空 < now 且 status≠destroyed | running 先 stop；置 expired |
| `autoDestroyAgentSandboxes`（:93-119） | fixedRate 1min | source=agent 且 autoDestroy=1 且 status≠destroyed 且 startedAt < now-300s（`agent-max-lifetime-seconds`） | provider.destroy |

**空闲判定 = `lastUsedAt`，仅 3 处更新**：create（:82）/start（:102）/execCommand（:147）——**文件 API（docker cp 读写）不更新 lastUsedAt** → 纯文件操作中的沙箱可能被误判空闲而停掉。
**dev 档 120 分钟空闲配置是死配置**：`gewu-sandbox/.../application.yml:75-76` 的 `dev.idle-timeout-minutes:120`、`dev.manual-ttl-days:30` 无任何 `@Value` 读取 → **dev 沙箱同样按全局 30 分钟被空闲 stop**。

### 3.5 自愈机制（`DevWorkspaceService.ensureDevSandboxForUser:112-168`）

- **故障 A（DB 状态滞后）**：DB 为 running 且探活通过（exec `echo ok` 5s，:171-179）→ 直接复用；running/stopped/expired → startSandbox 恢复（start 的 304 分支天然兼容"实际在跑"）。
- **故障 B（容器被外部删除）**：getSandbox/startSandbox 抛错 → catch 落到新建：source=dev + workspaceId + timeout 86400 + network true（:147-157）→ 重绑 `workspace.dev_sandbox_id`（:160-161）→ 重新注入 Git 凭证（:164，`injectGitCredentials:389-424`）。**旧沙箱 DB 行成为孤儿**；数据靠同名命名卷 `gewu-ws-{workspaceId}` 存续。
- 会话文件工具**不信任 dev_sandbox_id 直取**（`SessionFileWorkspaceService.resolveSandboxId:520-526` 注释明示），每次文件操作都走 ensure；另有 `ensureRunningSandbox:498-506` 供文件/Git 操作做"非 running 即 start"（无探活）。

### 3.6 一次性代码执行 executeCode（`SandboxService.java:165-215`）

按 language 解析模板 → 构造 CreateSandboxCommand（name=`agent-exec-{ts}`、source=agent、autoDestroy=1、timeout 30）→ create（**无 workspaceId → 不挂卷、只读根、网络关**）→ insert → start → 审计 EXECUTE_CODE → provider.exec（**因 source≠dev 用严格 CommandValidator**）→ **finally 中 destroy**（失败仅 warn）→ 返回 exitCode/stdout/stderr/duration。code 作为 `/bin/sh -c` 命令串执行。同一用户可并发多个 agent 沙箱（每次新建、无去重），存活窗口=执行时长，异常时最多泄漏 300 秒（调度器兜底强毁）。

### 3.7 生命周期配置全量（gewu-sandbox application.yml:46-78）

`internal-api-key`(:48)、`docker.host=unix:///var/run/docker.sock`(:50)、`defaults` cpu1/512M/1G/300s/`gewu/sandbox-base:latest`/network false(:51-57)、`registry`(:58-62)、`lifecycle` idle 30min/auto-stop true/manual-ttl 7d/project-ttl 0d/agent-max-lifetime 300s(:63-68)、`dev` image `gewu/dev-base:latest`/2C/4096M/20480M/network true/**idle 120min 与 ttl 30d 未接线**(:69-76)。

### 3.8 与设计文档的对照（27 号 V1.2 / 34 号）

**一致落地**：三种来源（manual/agent/project）、空闲 stop≠销毁、30min 空闲、manual 7 天、project 0 天、agent 300s、EXPIRED 标记+stop、V4 DDL、API 端点全集。
**未落地/偏差**：①来源实际 5 档（dev/workspace 为文档外新增）；②ERROR 状态规划"异常→ERROR→recover"从未写入；③dev 独立空闲策略配置存在未接线；④多运行时/预热池/快照恢复/监控告警全部未实现；⑤techStack 推断是死代码；⑥镜像白名单实际放开了公共镜像前缀（python:/node:/openjdk:/alpine:/ubuntu:，`SandboxConstants.java:19-27`）；⑦项目**删除**（非文档说的归档）时销毁沙箱（`ProjectService.deleteProject:156-163`）。

---

## 四、文件存储全景（按场景）

### 4.1 总原则：三种落点 + 一个零落点

| 落点 | 承载场景 | 特征 |
|---|---|---|
| **文件服务（MinIO，bucket `gewu-documents`）** | 用户上传、AI 产物、项目阶段文档、需求文档 | 对象存储 + DB 元数据 + 预签名 URL（7 天） |
| **沙箱（Docker 命名卷 `gewu-ws-{wid前12位}` → 容器 `/workspace`）** | 开发文件、会话文件工具产物、项目 git 仓库 | 真实文件系统（.git/inotify/进程），跨会话持久，宿主 Docker 卷承载 |
| **数据库（MySQL / pgvector）** | 会话消息/时间线/变更快照、执行账本、编排快照、审批、长期记忆 | 结构化过程数据 |
| 零落点 | executeCode 产物 | 结果字符串回流 LLM 对话，容器销毁即消失 |

**后端本地磁盘零业务写入**：全后端源码 grep `createTempFile|java.io.tmpdir|Files.write|FileOutputStream|FileWriter|RandomAccessFile|transferTo` 零命中；唯一本地落盘是部署脚本/日志框架产生的 `logs/`（无轮转配置）。

### 4.2 场景矩阵

| # | 场景 | 介质 | 路径/键 | 元数据表 | 访问方式 | 清理入口 |
|---|---|---|---|---|---|---|
| 1 | 用户上传 | MinIO | `workspaces/{userId}/{filePath}`（默认目录 src/docs/uploads 仅 DB 元数据） | workspace_file（parent_id 树）、workspace（配额） | content API / 预签名 7 天 | DELETE files/{id}（递归+回减配额；file_count 计数偏差） |
| 2 | AI 产物（无需求上下文） | MinIO | `workspaces/{userId}/ai-generated/{ai_{type}_{uuid8}}.{ext}`（目录懒建） | workspace_file | 消息内嵌 `<!--FILES:[...]-->` 文件卡片 | 删文件 API |
| 3 | AI 产物（需求内） | MinIO | `projects/{pid}/requirements/{rid}/docs/{fileName}` | requirement_file | 下载 API | 删单文件有；**删需求漏对象** |
| 4 | 项目阶段文档 | MinIO | `{pid}/{phaseCode}/{uuid8}_{fileName}` | phase_document(_version) | 预签名 | **删文档漏对象**（无前缀删除能力） |
| 5 | 会话消息/时间线 | MySQL | session_message.content（内嵌 FILES/PLAN 标记）+ metadata（process 时间线，上限 100 条） | session_message | 历史 API；前端解析还原 | **删会话漏** |
| 6 | 会话文件变更 | MySQL + dev 卷 | before 快照（session_file_change）+ 写入事件 churn（session_file_change_event，V47 新增）+ 当前内容在沙箱卷 | session_file_change(_event) | file-changes API（list/diff/content/turn/**undo**） | undoTurn 回合级撤销；删会话漏 |
| 7 | 任务/编排 | MySQL | orchestration_execution（graph_snapshot/final_output/token_used）+ approval_request | 同左 | 编排 API | **删图不级联**（execution/approval 残留）；orchestration_node_execution 为死表无写入 |
| 8 | Agent 执行账本 | MySQL | agent_execution（input/output/tool_calls/tokens_used/duration_ms） | agent_execution | — | 未见清理 |
| 9 | executeCode | **零落点** | 结果字符串回流 LLM；临时沙箱（不挂卷）容器销毁即消失 | 仅 sandbox_audit_log 一条 EXECUTE_CODE | 工具结果 | 随容器销毁 |
| 10 | 开发文件 | Docker 卷 | `/workspace/projects/{name}`（workspace_project）与 `/workspace/projects/{pid}/repo`（业务 project） | workspace_project、project | DevWorkspace API（exec cat / docker cp / rm -rf） | DELETE files（rm -rf）；**删项目漏卷内 repo** |
| 11 | wenshi 长期记忆 | pgvector | 6 张表（semantic_fragment/episodic_event/procedural_memory/experience/user_profile/reasoning_trace，HNSW） | 同左 | 认知路由 | 按片段 ID 删 |
| 12 | 服务日志 | 本地磁盘 | `logs/`（部署脚本重定向产生） | — | — | 无轮转配置 |

### 4.3 逐场景关键链路（要点）

1. **用户上传**：`POST /api/v1/workspaces/files/upload`（`WorkspaceController.java:54-60`）→ `WorkspaceService.uploadFile:182-223`（≤100MB、扩展名黑名单、配额 1G/5G(DEV,TESTER)/20G(ADMIN)、同名/路径校验）→ `MinioStorageService.uploadWorkspaceFile`（键=storagePath+filePath，父目录链拼接 `buildFilePath:541-553`）→ workspace_file 行 + used_bytes/file_count 累加。
2. **AI 产物**：SSE 推理完成后 `ContentClassifier.classify`（Planner 标注 FILE_OUTPUT，或代码块>50 行，或 >2000 字符且（表格>10 行或标题≥3 级））→ `FileOutputService.generateFiles` → docx 经 `WordDocumentService.markdownToWord`（后端 POI 进程内转换）→ 按有无 requirementId 路由（场景 2/3）；文件卡片元信息以 HTML 注释持久化在 assistant 消息 content 末尾（`AiChatController.java:562`），前端 ChatPage 解析恢复。
3. **会话数据**：用户消息请求入口即落库（幂等键 clientId）；助手消息流 doFinally 回调 persistTurn 落库（幂等键 `clientId:ai"`）。`session.directory` 写方 bindWorkspace（项目会话默认 `/workspace/projects/{pid}/repo`）、读方 resolveBindingOf 剥 `/workspace` 前缀作相对根；`session.workspace_id` 仅创建时写、沙箱解析实际走 createdBy→用户工作空间→dev 沙箱；**`session.path` 是死字段**（无读写方）。
4. **会话文件变更（本期演进）**：writeFile 时序——readFile 取 before → `snapshotBeforeTurnWrite`（回合前基线）→ `recordChange`（upsert 首次 before 快照）→ `recordWriteEvent`（**新增**：记 +N/-N 写入事件，turn_seq 关联活跃回合，内容无变化不产生事件，失败降级不影响主链路，`SessionFileWorkspaceService.java:84-111`）→ docker cp 写入沙箱。undoTurn（:289-351）按回合精确回退文件与事件。
5. **任务/编排**：`createExecutionEntity` 起手写入 graph_snapshot（图完整 JSON 副本，可重放）；结束写 final_output/token_used；取消时 variables 存取消快照；HITL 审批单行元数据。**编排不产生任何文件落盘**（三处 grep 无 MinioStorageService 引用）。
6. **executeCode**：`ToolExecutor:226` → `DbSandboxExecutorAdapter:24-32` → `SandboxClient.executeCode` → `SandboxService.executeCode:165-215`（临时沙箱 → exec → finally destroy）；stdout/stderr/exitCode 字符串拼接回流 LLM 工具循环，**不入库**；仅当内容进入助手回复时随 session_message 落库。
7. **开发**：`/api/v1/dev-workspaces`（sandbox/start、files CRUD、exec、projects/clone、pull/push/build/run）；两个"项目"概念的目录区别——workspace_project 按**名字** `projects/{name}`，业务 project 按 **ID** `projects/{pid}/repo`（`ProjectRepoService.java:39-41`，gitCommitPush/build/run 用后者）。

### 4.4 清理与泄漏全景（"删 X 时清理什么/漏什么"）

| 操作 | 清理 | 漏掉 |
|---|---|---|
| 删 workspace 文件 | 递归 workspace_file 行 + MinIO 对象 + 回减 used_bytes | file_count 只减 1（递归删多文件计数偏低，:436） |
| 删需求 | 逻辑删需求行 | requirement_file 行与 MinIO 对象不清理 |
| 删阶段文档 | version+doc 行 | MinIO `projects/{pid}/{phaseCode}/` 对象残留（**MinIO 无前缀批量删除能力**——漏删根因，仅单对象 deleteObject） |
| 删会话 | 仅删 session 行 | 消息、成员、变更记录(_event)、wenshi 记忆、卷内会话文件全残留 |
| 删项目 | 销毁 project 沙箱 + 删 project 行 | 卷内 `projects/{pid}/repo` 残留、MinIO 文档残留、关联会话不删 |
| 删编排图 | 仅删 graph 行 | orchestration_execution / approval_request 残留 |
| 删工作空间 | **不存在入口**（无 workspace 级 DELETE API） | 卷 `gewu-ws-*` 与 `workspaces/{userId}/` 前缀对象只能人工清 |

---

## 五、规则清单（速查）

**命令规则**：agent/manual/project/workspace 沙箱 → 严格验证器（禁 ;|&$`(){}<>\ 与 ||&&|><<>>$(${` 与 rm/mkfs/dd/shutdown/reboot/halt/poweroff/init/kill*/su/sudo 开头命令与换行）；dev 沙箱 → 仅禁 11 个破坏性子串，其余放行。
**路径规则**：沙箱文件 API 强制相对 /workspace、拒 `..`；会话文件工具 sanitizeRelative + UNSAFE_PATH 正则；MinIO 侧拒同名/`..`/绝对路径。
**TTL 规则**：manual 7 天过期标记（每小时扫，仅标记）；project 永不；dev 无 TTL 但 30 分钟空闲即 stop（探活自愈可恢复）；agent 300 秒强毁；一切沙箱空闲 30 分钟自动 stop（lastUsedAt 判定，文件操作不计入活跃）。
**配额规则**：MinIO 工作空间 1G/5G/20G 按角色、单文件 100MB、扩展名黑名单；Docker 卷**无磁盘配额**（disk-mb 仅记录）。
**隔离规则**：一用户一 workspace 一 dev 沙箱一卷；agent 沙箱可并发多个；卷永不随沙箱销毁删除。
**审计规则**：记 CREATE/START/STOP/DESTROY/COMMAND（明文）/EXECUTE_CODE/RENEW；不记文件访问与失败结果；无防篡改链。

---

## 六、问题汇总（分级，跨安全/生命周期/存储）

**P0（建议立即处理）**：①DevWorkspaceService 路径未净化拼 shell（注入）；②exec 超时不杀进程且退出码默认 0；③SandboxProxyController 源码损坏（`/glm-5.3_common` 字面量，按 id 代理端点全失效）。
**P1**：④默认/硬编码密钥入仓；⑤镜像白名单与资源上限校验未接线（SandboxValidator 死代码）；⑥文件访问审计缺失+result 恒 SUCCESS+SM3 哈希链未实现；⑦k8s NetworkPolicy 选择器不匹配沙箱 pod；⑧存储只增不减（卷永不删、删会话/项目/需求/图均有残留、MinIO 无前缀删除）；⑨dev 档空闲策略死配置（120min 未生效，实际 30min 停，与设计文档 2h 意图不符）。
**P2**：⑩死代码/死表/死字段（ERROR 枚举、orchestration_node_execution、session.path、agentId、SandboxSecurityPolicy、resolveFromTechStack）；⑪镜像默认值三处不一致（sandbox-base vs sandbox；alpine vs dev-base）；⑫lastUsedAt 不含文件操作；⑬dev-base root 运行、无 pids/磁盘配额；⑭file_count 计数偏差；⑮命令/token 审计明文无脱敏。

---

## 七、证据与置信度

- **A 级（本人逐行复核）**：DockerSandboxProvider（全文 239 行）、SandboxScheduler（全文 119 行）、CommandValidator（全文）、DevCommandValidator（全文）、SessionFileWorkspaceService 头部 130 行（WORKSPACE_ROOT/UNSAFE_PATH/recordWriteEvent 写入链）、SandboxProxyController 损坏字面量（grep 直证）、SM3 未实现（全库无 setLogHash）、SandboxValidator 死代码（全库无调用）、@EnableMethodSecurity 缺失（grep 直证）、session_file_change_event（V47+实体+Mapper 存在）。
- **A 级（双代理独立一致+行号吻合）**：SandboxService（TTL/审计/executeCode/renewExpire）、DevWorkspaceService（自愈/凭证注入/文件 API）、WorkspaceService/FileOutputService/RequirementFileService 上传与产物链路、清理缺口矩阵。
- **B 级（单源推断）**：k8s/helm 网络策略盲区细节、镜像默认值三处不一致的具体引用行、`docker/Dockerfile.dev` 内容清单。
- **明确未找到**：pids/ulimits/withUser 设置、自定义 seccomp/apparmor、firecracker/gvisor 实现、监控指标与告警、快照恢复、工作空间删除入口、MinIO 前缀批量删除。
