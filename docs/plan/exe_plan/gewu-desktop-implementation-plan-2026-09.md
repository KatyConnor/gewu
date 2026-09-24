# gewu-desktop 详细实施计划

> **文档编号**：EXE-DESKTOP-2026-Q4 | **版本**：V1.1 | **编制日期**：2026-09-09
> **状态**：🚀 待启动（V1.1：平台演进后再基线）
> **架构基线**：`docs/design/42-gewu-desktop-architecture-design.md`（**V3.0**，ADR-001~009 定稿）
> **角色**：项目管理与敏捷教练（角色10）+ 资深技术架构师（角色03，技术任务核定）
> **维护者**：架构组
> **估时口径**：1 人日 = 1 名合格开发的全时工作日；所有估算预留 10% 调整空间
>
> **修订记录**：
>
> | 版本 | 日期 | 修订内容 |
> |------|------|----------|
> | V1.0 | 2026-09-09 | 初版：Phase 0~3 全量 WBS、四里程碑门禁、依赖拓扑、风险登记册 |
> | V1.1 | 2026-09-09 | **平台演进再基线**（对齐架构 V3.0）：① 里程碑顺延 1 周（M0 09-23 / M1 11-06 / M2 12-04 / M3 12-31），桌面总量 70→74 人日；② 平台侧任务清单 15→6.25 人日（E1/E2/E3 已由平台完成出清单，N4 转为验证项，新增 V47 收敛依赖项）；③ Phase 0 共享包抽离范围按 S9 后现协议与组件族重勘（D0-8/9/11 扩容）；④ Phase 1 新增 LocalFileWorkspaceSpi（ADR-009 自加固）与本地变更追踪任务，FileEditorPanel 改为复用；⑤ Phase 2 新增桌面预算 profile/配额预检/编排生命周期任务；⑥ 风险登记册更新（RK-1 消除，新增 RK-10~12） |

---

## 目录

1. [计划概述与前提](#一计划概述与前提)
2. [里程碑总表与验收门禁](#二里程碑总表与验收门禁)
3. [Phase 0 详细任务](#三phase-0-详细任务)
4. [Phase 1 详细任务](#四phase-1-详细任务)
5. [Phase 2 详细任务](#五phase-2-详细任务)
6. [Phase 3 详细任务](#六phase-3-详细任务)
7. [平台侧配套任务清单](#七平台侧配套任务清单)
8. [依赖关系与关键路径](#八依赖关系与关键路径)
9. [风险登记册](#九风险登记册)
10. [质量门禁与测试策略](#十质量门禁与测试策略)
11. [进度跟踪机制](#十一进度跟踪机制)
12. [Phase 4 Backlog](#十二phase-4-backlog)

---

## 一、计划概述与前提

### 1.1 交付目标

| 里程碑 | 交付物 | 目标日期 |
|--------|--------|----------|
| M0 | Spike 结论（Go/No-Go）+ 共享包 v0.1（S9 组件族纳入） | **2026-09-23** |
| M1 | 单机完整对话+工具+文件工作流闭环安装包（三平台内测版） | **2026-11-06** |
| M2 | 单机全能力版 + 协同基础（模型共享/会话同步/配额预检） | **2026-12-04** |
| M3 | 正式版发布（协同完整 + 分发链路 + 信创 arm64） | **2026-12-31** |

> 日历口径：2026-09-14（周一）启动；国庆假期（10-01~10-07）不计工期已扣除；桌面总量 74 人日（V1.1 净增 +4，源自文件工具 SPI 自加固、变更追踪、S9 组件族接入）；含 10% 缓冲后 M3 最迟滑至 2027-01 中旬。

### 1.2 人力假设与两种情景

| 情景 | 配置 | 桌面关键路径 | 平台配套任务 |
|------|------|--------------|--------------|
| **情景 A（基准）** | 桌面 1 名全时（TS+Java 双栈）；平台侧任务（PT 系列 ~6.25 人日）由平台迭代人力池并行消化 | **15 周**（本计划排期） | 按 §七 窗口并行 |
| 情景 B（单人力包干） | 同 1 人兼做桌面与平台配套 | **~16 周**（+6.25 人日串行） | 串行插入（M1 +1.5d / M2 +4d / M3 +0.5d） |

### 1.3 启动前核实项（W37 第一天完成；V1.0 三项已由平台演进解决，替换为新核实项）

| # | 核实项 | 状态 | 说明 |
|---|--------|------|------|
| ~~V-1 引擎测试现状~~ | ✅ 已核实 | JaCoCo 棘轮门禁已建（BUNDLE ≥0.45 / core·budget·llm·tool·security ≥0.70 / orchestration ≥0.40），转化为持续基线而非核实项 |
| ~~V-2 D-2 修复状态~~ | ✅ 已完成 | `Message.toolCalls` + 序列化已合入；桌面侧仅余三厂商回归（M1 门禁） |
| ~~V-3 双引擎收敛链路~~ | ✅ 已完成 | legacy 委托 ReactAgentExecutor |
| **V-4（新）** | **平台工作区未提交变更清单冻结**：确认 V47（回合撤销）、admin 拆分、预算增量、B 组 API 收敛的收敛计划与时间点 | ⏳ W37 | 桌面以已提交 HEAD（`d5e72c7`）为基线；依赖未提交能力的任务（TurnChangesBar/undo、配额 UI 细节）标"待收敛"（RK-10） |
| **V-5（新）** | **T5.1 SSE 分布式广播（SseBroadcastService）实况验证**：多副本下 approval_required/message 事件可达性 | ⏳ W37~W39 | 决定 D3-1 是否需要额外适配 |
| **V-6（新）** | **S9 文件工具行为基线快照**：内置工具结果文案、`+N/-N` 口径、V39 变更表语义、diff 算法——提取为共享 fixture（ADR-009 对齐依据） | ⏳ W37 | D1-15/D1-23 的契约输入 |

### 1.4 前置条件（长周期项立即启动）

| # | 前置项 | 启动时点 | 说明 |
|---|--------|----------|------|
| P-1 | **代码签名证书采购**（Windows Authenticode + Apple Developer） | **W37 当日** | 周期 2~6 周，M3 硬前置 |
| P-2 | 构建机（macOS x64+arm64 / Windows / Linux x64+arm64 毕昇 JDK 21） | W37 | — |
| P-3 | LLM 厂商测试 Key（DeepSeek/智谱） | W37 | Spike-A 与 M1 回归 |
| ~~P-4 引擎 Maven 仓库开通~~ | **降为可选** | — | desktop-runtime 为 monorepo 内 Maven 模块，`mvn -pl gewu-desktop-runtime -am package` 可绕过；仅独立 CI 检出场景需要（PT-E4 可选） |

### 1.5 范围外

Phase 4 Backlog（§十二）、平台业务域迭代、管理后台域（已拆分至 admin-server/admin-web，桌面不承载）、视觉规范专项、移动端。

---

## 二、里程碑总表与验收门禁

| 里程碑 | 验收门禁（全部满足方可进入下一阶段） |
|--------|--------------------------------------|
| **M0**（09-23） | ① Spike-A：引擎 NoOp 启动 + 最小 chat/stream + jlink JRE ≤60MB + 启动 ≤3s；② Go 评审通过；③ 共享包构建绿 + web 回归绿（含 S9 组件族直迁项）；④ 协议 fixture 固定于 HEAD 快照（V-6）；⑤ ADR-001 复决条件复查（Electron arm64 构建验证） |
| **M1**（11-06） | ① 单机 E2E：对话→**内置文件工具写→授权弹窗→diff 预览→执行→变更表落库→右侧面板审查**→重启恢复；② 安全门禁：**LocalFileWorkspaceSpi 专项用例**（路径逃逸/授权拒绝/审计完整性）+ 注入/黑名单/令牌用例；③ 三平台安装包可用 + 引擎守护可用；④ 契约测试基线（含新事件：content_reset 清场序列/plan 序列/finishReason 分支/ping 穿透）；⑤ 性能：引擎 ≤2.5s、首 token ≤1.5s；⑥ **三厂商多轮工具调用回归**（D-2 验证） |
| **M2**（12-04） | ① 本地编排 E2E：PIPELINE/SUPERVISOR + **pause/resume/cancel 生命周期**；② 会话同步幂等重放零副作用（**携带 S9 五列**：project_id/workspace_id/directory/pinned/time_archived）；③ 模型双源切换 + **配额预检联调**；④ 沙箱 ADR-008 对齐清单逐项验证（CMD 覆盖/304 幂等/mkdir -p/限额/自愈）+ 无 Docker 降级外显；⑤ 版本协商握手三态可用 |
| **M3**（12-31） | ① 分发链路演练（签名安装→更新→回退）；② linux-arm64 信创包；③ 私有化内网源 + 离线包；④ NFR 全指标实测（安装包 ≤200MB 含 Monaco / 内存 ≤800MB）；⑤ 文档齐备（发布说明与引擎版本对齐） |

---

## 三、Phase 0 详细任务

> **周期**：W37-W38（2026-09-14 ~ 09-23），8 人日 | **目标**：三大不确定性去险 + 共享包 v0.1（S9 后范围）。

### Spike-A：引擎单机启动 PoC

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D0-1 | 引擎独立启动验证 | 最小 Spring Boot 宿主 + engine；NoOp 默认装配启动（**含 S9 新增 Bean：FileWorkspaceSpi NoOp/ContextCompactor NoOp/CostCalculator 缺省**）；Bean 清单 | 0.5d | — |
| D0-2 | 最小 chat/stream 端点 | 透传 ReactAgentExecutor 流式（**以方案 A 后的预算行为实测**：token 记账/续期/finishReason）；SSE 全事件类型核验（含 ping/content_reset/plan_*） | 0.5d | D0-1, P-3 |
| D0-3 | jlink + AppCDS 实测 | 模块清单/体积/启动耗时 | 0.5d | D0-1 |
| D0-4 | Go/No-Go 评审 | 门禁核验；不达标走降级路径（裁剪可选依赖/完整 JRE）；触发 ADR-002B 复审条件评估 | 0.5d | D0-1~3 |

### Spike-B 与基线固定

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D0-5 | DTO 下沉评估（E5 输入） | 引用面扫描（**范围含 S9 新事件与 PlanStepInfo 等嵌套类型**）；gewu-common vs 新模块决策 | 0.5d | V-4 |
| D0-6 | HEAD 基线固定 | 以 `d5e72c7` 固定协议 fixture 快照（事件全集/内置工具文案/变更表语义，V-6 产物入共享包 protocol 测试资源）；未提交能力登记为"待收敛"清单 | 0.25d | V-4 |

### 共享包抽离（S9 后范围重勘）

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D0-7 | workspace 脚手架 | 根 pnpm-workspace.yaml + `packages/agent-ui` 骨架（gewu-web 现为自持 pnpm-lock，需建立 workspace） | 0.5d | — |
| D0-8 | protocol 包 | **按现行事件全集重勘**：基础 8 种 + content_reset/plan_*/subagent_status/budget_*/finishReason/metadata(预留)/ping；Result/错误码；IPC zod schema；Profile 端点清单 | 1d | D0-6 |
| D0-9 | api-client | 统一 HTTP（凭据可插拔）+ **consumeChatSse 自 chat.ts 抽离**（543 行版：ping 忽略/60s 看门狗/clientId/content_reset 分发/plan 回调/预算回调/finishReason） | 1.25d | D0-8 |
| D0-10 | process 包 | ProcessTracker（含 resetContent/splitFinalContent）+ **parseProcessFromMetadata**（过程时间线持久化还原） | 0.5d | D0-7 |
| D0-11 | chat-ui 组件族（第一步抽离） | AIProcessTimeline（S9-M2 形态）+ ProcessEditDiff + lineDiff/diffHunks + **SessionSidebar** + **PlanCard** + **FileEditorPanel/MonacoEditor/MonacoDiff**（next/dynamic 懒加载替换 + monaco 资源随包）+ ChatErrorBanner；均为纯 props/命令对象驱动（已验证解耦） | 1.5d | D0-9, D0-10 |
| D0-12 | ui + theme | MarkdownRenderer/MarkdownReader/FileCard/Toast/Select/themes | 0.5d | D0-7 |
| D0-13 | web 接入验证 | web chat 路径切共享包；回归（**ChatPage 壳暂不抽离，两步策略第一步只抽组件族**）；发共享包 v0.1.0 | 0.75d | D0-8~12 |

**Phase 0 合计：8 人日**。并行：P-1 证书采购、P-2 构建机、V-4~V-6 核实。

---

## 四、Phase 1 详细任务

> **周期**：W39-W44（2026-09-24 ~ 11-06，27 人日，扣国庆） | **目标**：单机完整闭环（对话+工具+**文件工作流**）。

### 壳层骨架（6d）

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D1-1 | Electron 工程初始化 | electron-vite 三目标；严格 TS；lint 对齐 zcode-rules | 0.5d | D0-13 |
| D1-2 | 主进程骨架 | 窗口/生命周期/单实例/托盘/菜单；CSP（connect-src 白名单） | 1d | D1-1 |
| D1-3 | EngineSupervisor MVP | spawn jlink+jar；stdin 引导（port/token/secrets）；健康心跳；退出码分类+重启 ≤3 | 1d | D0-4 |
| D1-4 | SecretVault | safeStorage + SM4 双层；`secret:*` IPC | 0.75d | D1-2 |
| D1-5 | preload + IPC 注册中心 | contextBridge 类型化 API；通道 handler 骨架；zod 校验 | 0.75d | D1-2 |
| D1-6 | App Shell | 布局/工作区 Tab/desktopSlice（+**plan 状态按会话隔离/filePanel 命令/turnChanges 预留**） | 1d | D1-5 |
| D1-7 | 工作区管理 | 选择器 + AGENTS.md 检测 + `workspace:*` IPC | 1d | D1-6 |

### desktop-runtime MVP（7d；原 E1/E2 阻塞已解除）

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D1-8 | runtime 模块脚手架 | Maven 模块（monorepo `mvn -pl` 构建）；引擎装配（桌面 SPI 覆盖 NoOp）；临时令牌过滤器；127.0.0.1 随机端口 | 1d | D0-4 |
| D1-9 | H2 持久化 | Flyway schema：local_session（**含 S9 五列**）/local_message（client_id+seq，镜像 V33 唯一键）/local_audit/local_usage/kv；DesktopPersistenceService | 1.5d | D1-8 |
| D1-10 | DesktopSessionContextService | 历史构建（50 条可配） | 0.5d | D1-9 |
| D1-11 | DesktopLocalLlmProvider | 模型配置管理；Key 内存注入；**三厂商多轮工具调用冒烟**（D-2 修复验证） | 1d | P-3 |
| D1-12 | Chat/Session/Models 端点 | Profile 三组端点 + SSE 直写（协议 DTO 按 PT-E5 下沉产物复用）+ 渲染层联调第一句话 | 1d | D1-9~11, PT-E5 |
| D1-13 | Profile 契约测试基线 | 共享 fixture 双端断言（**含新事件序列**）；CI 挂钩 | 1d | D1-12 |

### 本地工具集（5.5d，ADR-009 结构）

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D1-14 | 工具脚手架 | @ToolProvider 注册验证；工具定义 schema 规范 | 0.5d | D1-8 |
| D1-15 | **LocalFileWorkspaceSpi**（核心） | 实现 FileWorkspaceSpi：本地 fs 读写；**SPI 内自加固三件套**：PathGuard（realpath+符号链接逃逸检测+工作区根约束）/写前授权（PermissionService→弹窗+diff 预览）/调用级审计；**写路径埋变更记录**（§7.5）；工具名/参数/结果文案与平台逐字一致（V-6 fixture 对齐） | 2d | D1-14, D1-19 |
| D1-16 | fs.glob / fs.grep | @ToolProvider 补齐引擎未内置检索能力（走五段管线） | 0.5d | D1-14 |
| D1-17 | shell.exec | ProcessBuilder（超时/进程组清理）；CommandValidator（16 条黑名单共享规则文件） | 1d | D1-14 |
| D1-18 | git.* + project.context | git CLI 封装；AGENTS.md→rules→.gewu/skills 装载器 | 1d | D1-14 |
| D1-19 | 输出管线收尾 | 截断 10KB / PII 脱敏 / local_audit 哈希链列 | 0.5d | D1-15~18 |

### 权限与授权流（2.5d）

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D1-20 | DesktopPermissionService | 三级模式 + alwaysAllow 会话白名单（kv） | 1d | D1-8 |
| D1-21 | DesktopHitlGateway | SSE approval_required → 弹窗 → POST 恢复 Mono（30min 超时，对齐平台语义） | 1d | D1-20 |
| D1-22 | PermissionDialog | 工具/动作/target + diff 预览 + alwaysAllow（参考 git 6def6aa 重写）；**覆盖 SPI 写路径用例**（D1-15 联调） | 0.5d | D1-21, D1-15 |

### 本地变更追踪（1.5d，架构 §7.5）

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D1-23 | 变更存储与端点 | H2 `local_file_change`（镜像 V39 语义）；DesktopFileChangeController（列表//diff//content；**/turn//undo 按 V47 收敛状态标预留**）；diff 算法移植（LCS+unified，对齐 lineDiff 口径） | 1.5d | D1-15, V-6 |

### 桌面 UI 主链路（4.5d）

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D1-24 | 聊天工作台集成 | 共享包组件族组装 + ConnectionManager（单机模式）；运行时指示器；**plan/预算/统计事件接线** | 1.25d | D1-12, D1-22 |
| D1-25 | 会话管理 UI | 本地会话列表（**项目分组/置顶/归档视图，SessionSidebar 直用**） | 0.75d | D1-24 |
| D1-26 | 文件面板集成 | **FileEditorPanel + Monaco 复用**（file-changes 本地端点）；"N 个文件已更改"chip | 0.75d | D1-23 |
| D1-27 | 终端面板 | xterm.js + node-pty（多 Tab；命令复现） | 0.75d | D1-2 |
| D1-28 | M1 集成验收 | 三平台出包；E2E 门禁①~⑥逐项 | 1d | 全部 |

**Phase 1 合计：27 人日**（壳层 6 + runtime 7 + 工具 5.5 + 权限 2.5 + 变更追踪 1.5 + UI 4.5）。

---

## 五、Phase 2 详细任务

> **周期**：W45-W49（2026-11-09 ~ 12-04，20 人日） | **目标**：单机全能力 + 协同基础。

### 沙箱与 MCP（4.5d）

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D2-1 | LocalDockerSandboxExecutor | docker CLI 封装；**ADR-008 对齐清单逐项**（CMD tail 覆盖/304 幂等/mkdir -p/限额 1C-512M-1G-300s/安全 opt/卷挂载/探活自愈）；无 Docker 降级外显 | 1.5d | D1-19 |
| D2-2 | 本地 MCP 管理 | DesktopMcpConfigSource + 管理页；**stdio + streamable_http**（引擎 StreamableHttpClient 已实现）；sse 仅兼容标注 | 1.25d | D1-8 |
| D2-3 | code_execute 接入 | 经 SandboxExecutor SPI；CodeScannerCheck 用例 | 0.5d | D2-1 |
| D2-4 | MCP 工具授权 | 首用授权 + 来源审计 | 0.5d | D2-2, D1-20 |
| D2-5 | SandboxPage（本地） | 状态/资源占用 | 0.75d | D2-1 |

### 编排与 Agent/技能管理（5d）

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D2-6 | Orchestration 端点 | 图 CRUD/execute/stream/goals + **执行生命周期（pause/resume/cancel——引擎已真实现）**；检查点策略决策（桌面单机=内存态，重启放弃并 UI 明示） | 1.5d | D1-12 |
| D2-7 | 编排工作台 | OrchestrationPage/Canvas 复用接本地；**生命周期按钮接真实端点** | 1.25d | D2-6 |
| D2-8 | 本地 Agent 管理 | CRUD/工具技能挂载/prompt 编辑 | 1d | D1-12 |
| D2-9 | 本地技能库 | CRUD + .gewu/skills 扫描安装 | 0.75d | D2-8 |
| D2-10 | 记忆 v1 | DesktopMemoryStore（TF-IDF）+ MemoryRouter 验证 | 0.5d | D1-9 |

### 协同基础·平台联调（4.75d）

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D2-11 | DesktopProxyLlmProvider | 双源实现 + LLM 代理联调（流式/工具/usage/预算） | 1d | PT-N1, D1-11 |
| D2-12 | 会话上行同步 | 批量上行（client_id 幂等——**平台 V33 已就绪**）；**携带 S9 五列** | 1d | PT-N2 |
| D2-13 | 会话下行与接续 | 增量拉取；接续为本地会话 | 1d | PT-N2 |
| D2-14 | 执行记录上报 | 复用 /agents/executions | 0.5d | — |
| D2-15 | 资源清单合并 | 平台 Agent/技能/MCP 拉取 + ETag 缓存合并 | 1.25d | D2-8, D2-9 |

### 协同基础·桌面收口（5.75d）

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D2-16 | ConnectionManager 平台模式 | 登录/自动刷新/设备标识（**X-Device-Id 桌面侧先行，平台记录仍待做**） | 1d | D2-15 |
| D2-17 | 连接向导 UI | gateway→账号→设备命名；游客默认；**版本协商三态（PT-N9）** | 1d | D2-16 |
| D2-18 | 用量仪表盘 | H2 聚合 + UsagePage 复用 | 0.75d | D1-9 |
| D2-19 | 设置页完整化 | 连接/模型源/权限/同步/更新通道 | 0.5d | D2-17 |
| D2-20 | **桌面预算 profile + 配额预检** | application.yml 预算默认值（tokenBudget 放宽/quotaBlockEnabled=false/costBudgetYuan=0）；DesktopCostCalculator 本地单价表；**协同形态 /users/me/quota 预检接入** | 1d | D2-11 |
| D2-21 | 回合撤销（预留） | **V47/TurnChangesBar/undo 接入——以平台收敛为前提（PT-V47）**，未收敛则顺延 Phase 3 | 0.5d | PT-V47 |
| D2-22 | M2 集成验收 | 门禁①~⑤ | 1d | 全部 |

**Phase 2 合计：20 人日**。

---

## 六、Phase 3 详细任务

> **周期**：W50-W53（2026-12-07 ~ 12-31，19 人日） | **目标**：协同完整化 + 分发 + 信创。

### 协作会话与远程审批（6d）

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D3-1 | 协作会话参与 | SSE 订阅（message/approval_required）+ 发消息 + 云端 Tab；**V-5 验证结论落地（T5.1 分布式广播）** | 1.25d | D2-16, V-5 |
| D3-2 | 统一审批中心 | 本地工具授权/本地编排 HITL/远程审批三类收口 + **配额提示** | 1.5d | D3-1, D1-22 |
| D3-3 | 云端执行路由 | 显式选择云端 Agent → 网关 chat/stream（渲染层透明） | 1d | D2-16 |
| D3-4 | 云端沙箱回退 | 本地缺失 → /sandboxes | 0.75d | D2-5 |
| D3-5 | Agent 市场页 | 平台浏览/安装引导；**归档/置顶/分享/重新生成协同复用** | 1.5d | D2-15 |

### 平台资源导入（4d）

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D3-6 | 平台 Agent 导入本地 | 导入向导 + 工具本地可用性检测标注 | 1.5d | D2-15 |
| D3-7 | 平台技能安装 | 到本地库/.gewu/skills | 0.75d | D2-9 |
| D3-8 | 平台 MCP 本地化 | stdio 一键本地拉起；streamable_http 标注 | 1.25d | D2-4 |
| D3-9 | 中期回归 | 协同场景冒烟 | 0.5d | D3-6~8 |

### 分发工程（5d）

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D3-10 | 打包配置完善 | electron-builder 正式配置；jlink JRE + jar + **monaco 资源**打入；manifest 模式对齐 scripts/package.sh | 1d | P-2 |
| D3-11 | 签名链路 | Windows Authenticode / macOS 公证 / Linux + jar sha512 清单；spawn 前校验 | 1d | P-1 |
| D3-12 | 自动更新接入 | 双通道 + L2 引擎 jar 独立替换（校验+回退）+ 强制升级引导 | 1d | D3-10 |
| D3-13 | Jenkins desktop stage | pnpm → mvn -pl runtime → electron-builder → 签名 → MinIO + latest.yml | 1.5d | D3-10 |
| D3-14 | 更新演练 | 安装→更新→回退全链路 | 0.5d | D3-11~12 |

### 信创与发布（4d）

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D3-15 | linux-arm64 构建 | 毕昇 JDK jlink + Electron arm64；麒麟/UOS 冒烟 | 1.5d | D3-10 |
| D3-16 | 私有化交付包 | 内网更新源 + 离线包 + 版本留存 | 0.5d | D3-13 |
| D3-17 | 文档 | 安装指南/用户手册/私有化附录/发布说明（引擎版本对齐，标注 SUBGRAPH 等简化项） | 1.5d | — |
| D3-18 | M3 验收与发布 | NFR 实测 + 全量回归 + 正式 tag | 0.5d | 全部 |

**Phase 3 合计：19 人日**。

---

## 七、平台侧配套任务清单（V1.1 重写：15 → 6.25 人日）

> E1（D-2）/ E2（D-10）/ E3（D-3）**已由平台完成并出清单**；N4（SSE 分布式广播）已由 T5.1 实施、转为验证项。

| 任务ID | 内容 | 工作量 | 完成期限 | 阻塞的桌面任务 |
|--------|------|--------|----------|----------------|
| PT-E5 | 协议 DTO 下沉（gewu-common 或独立模块）——**范围含 S9 新事件与嵌套类型**（按 D0-5 结论执行） | 1.5d | **W41 末（10-16）** | D1-12, D1-13 |
| PT-N1 | LLM 代理端点（SSE 归一化 chunk + 预算 + usage 记账） | 2d | **W47 末（11-20）** | D2-11 |
| PT-N2 | DesktopSyncController（上行/下行/用量；**携带 S9 五列**） | 2d | W47 末 | D2-12, D2-13 |
| PT-N9 | `/platform/info` 版本协商端点 | 0.5d | W48 末（11-27） | D2-17 |
| PT-N4v | T5.1 分布式广播联调验证支持 | 0.25d | W50（12-07） | D3-1 |
| PT-V47 | **回合撤销收敛**（V47 迁移 + /turn + /undo 提交） | 平台排期 | Phase 2 复核（W48） | D2-21（预留） |
| PT-E4 | 引擎产物 Maven 发布（**可选**，monorepo 构建可绕过） | 0.5d | 可选 | — |
| PT-N8 | 审计上送端点（Phase 4） | 1d | P4 | — |

---

## 八、依赖关系与关键路径

```
P-1 证书(最长提前期链) ──────────────────────────────────┐
V-4~V-6 核实/快照 ─► D0-1~5 Spike ─► M0(09-23) ─┬► D1-1~7 壳层 ─┐
                 D0-6~13 共享包 ────────────────┘               │
                                          D1-8~13 runtime（◄ PT-E5 @10-16）
                                          D1-14~19 工具（核心 D1-15 SPI）
                                          D1-20~23 权限+变更追踪
                                                        ▼
                                   D1-24~28 UI 主链路 ═══ M1(11-06) ═══
                                                        ▼
        D2-1~10 沙箱/MCP/编排/管理 ─► D2-11~15 协同联调（◄ PT-N1/N2 @11-20）
                                   ─► D2-16~22 收口（◄ PT-N9/V47）═══ M2(12-04) ═══
                                                        ▼
        D3-1~5 协作/审批（◄ V-5 验证）─► D3-6~9 导入 ─► D3-10~14 分发（◄ P-1）
                                   ─► D3-15~18 信创/发布 ═══ M3(12-31) ═══
```

**关键路径**：P-1 证书采购 → D3-11 签名 → D3-14 演练 → M3；开发线：D0-4 Go → D1-8 → D1-12（卡 PT-E5）→ D1-24 → M1 → D2-12（卡 PT-N2）→ M2 → D3-1（卡 V-5 验证）→ M3。平台硬期限三个：**PT-E5 @10-16、PT-N1/N2 @11-20、PT-N9 @11-27**，逾期 1:1 顺延对应里程碑。

**并行度**：周内 Java（runtime/工具/SPI）与 TS（壳/组件/联调）交错；UI 联调排在对应后端任务当周尾。

---

## 九、风险登记册（V1.1 更新）

| ID | 风险 | 触发器 | 缓解动作 | 兜底路径 |
|----|------|--------|----------|----------|
| ~~RK-1 PT-E1 逾期~~ | ✅ 消除 | D-2 已由平台修复 | — | — |
| RK-2 引擎测试覆盖 | 🟡 降级 | JaCoCo 棘轮已建（core ≥0.70）；适配层单测随任务交付（D1-13/D2-22）+ 契约测试 CI | M1 后专项窗口 | |
| RK-3 jlink 体积/启动 | 🟠 维持 | D0-3 实测超标 | 降级路径（裁剪/完整 JRE）；**新增 Monaco 体积项**（安装包预算已调 200MB） | ADR-002B 复审 |
| RK-4 Profile 漂移 | 🟠 维持 | 契约测试失败 | PT-E5 强制前置（**范围含新事件**）；fixture 固定 HEAD | 冻结协议变更 |
| RK-5 单人带宽 | 🟠 维持 | 周完成 <80% | 裁剪序：M1 不可砍 → 记忆 v1/用量后移 → 信创 arm64 后移 | 情景 B 规则 |
| RK-6 LLM 代理负载 | 🟡 维持 | 限流频繁 | 独立预算上限 + 50 并发压测 | 回落本地 Key |
| RK-7 Electron CVE | 🟡 维持 | 安全通告 | 季度评估 + Dependabot | — |
| RK-8 信创 arm64 | 🟡 维持 | D3-15 冒烟失败 | 毕昇 JDK + Electron arm64 先行 | 后移 Phase 4 |
| RK-9 引擎简化项感知 | 🟡 收窄 | 内测反馈 | 仅剩 SUBGRAPH/LlmGoalPlanner 默认关；发布说明对齐 | — |
| **RK-10 平台未提交变更悬空** | 🟠 新增 | W48 时 V47/admin 拆分仍未收敛 | HEAD 基线开发（D0-6）；依赖项标"待收敛"（D2-21 预留）；双周对齐收敛计划 | 回合撤销移 Phase 4 |
| **RK-11 内置工具管线绕过安全实现** | 🟠 新增 | D1-15 安全用例失败 | SPI 自加固三件套 + 专项测试（M1 门禁②）+ 双人 review | 兜底：available()=false 回退全 @ToolProvider 方案（UX 降级可接受） |
| **RK-12 ChatPage 抽离复杂度** | 🟠 新增 | D0-11 超期 >50% | 两步抽离（组件族先行——已验证解耦；状态机 hook 化渐进）；桌面 Phase 1 仅依赖第一步 | 组件族直用 + ChatPage 壳桌面自写轻量版 |

---

## 十、质量门禁与测试策略

（分层策略与 V1.0 一致：runtime JUnit ≥70% / 契约测试 CI 门禁 / 共享包 vitest / IPC zod / E2E 冒烟清单 / 性能复测。V1.1 增补：）

| 增补项 | 内容 |
|--------|------|
| 契约 fixture 扩容 | content_reset 清场序列、plan_created/updated 序列、finishReason 五分支、ping 穿透、subagent_status、预算告警文案——固定于 HEAD 快照（D0-6），PT-E5 下沉后切换共享 DTO |
| **LocalFileWorkspaceSpi 安全专项**（M1 门禁②） | 路径逃逸（`..`/符号链接/绝对路径/跨工作区）× 授权（READ_ONLY 写拒绝/WRITE 弹窗/FULL_AUTO 审计）× 变更记录完整性（CREATE/MODIFY/DELETE + before_snapshot 正确性）× 审计哈希链 |
| 沙箱对齐清单测试（M2 门禁④） | ADR-008 表逐项自动化用例 |
| 预算行为测试 | token 记账真实化后的告警/续期/轮次扩容事件序列；quotaBlockEnabled 开关两态 |
| 性能复测 | 每里程碑：引擎启动/首 token/内存/安装包体积（含 Monaco 增量核对） |

安全检查清单、缺陷分级（P0 阻断里程碑 / P1 清零 / P2 复核）与 done 归档惯例（`docs/plan/done/gewu-desktop-phaseN-done.md`）维持 V1.0。

---

## 十一、进度跟踪机制

（同 V1.0：周计划/周报（✅/🔄/⏸/⛔/➡️ 状态标记）、里程碑评审、双周平台对齐（**新增：V47 收敛与 admin 拆分专题**）、计划变更纪律——里程碑日期/scope/人力情景切换须修订留痕。）

---

## 十二、Phase 4 Backlog

| 条目 | 前置 |
|------|------|
| 记忆 v2（本地 ONNX 嵌入） | M3 |
| 审计上送平台哈希链（PT-N8） | 平台端点 |
| 本地 Agent 发布平台市场 | 无 |
| 企业策略包下发 | 平台端点 |
| 多端会话移交（gewu:// 深链） | 无 |
| 引擎 jar 独立更新通道常态化 | D3-12 |
| Monaco 只读浏览深化 / Playwright-Electron E2E | M3 |
| loongarch 信创包 | 龙芯 JDK 21 + Electron loong64 生态 |
| **回合撤销完善（若 RK-10 兜底触发）** | PT-V47 |

---

*执行入口：W37（2026-09-14）启动日检查单——① V-4~V-6 新核实项 ② P-1 证书采购下单 ③ P-2/P-3 就绪 ④ 本计划 V1.1 基线确认*
