# gewu-desktop 桌面端模块架构设计

> **文档编号**：42 | **版本**：V3.0 | **日期**：2026-09-09
> **设计角色**：资深技术架构师（角色03）
> **状态**：评审中
> **前置阅读**：`docs/design/01-technical-architecture.md`、`docs/design/21-unified-architecture.md`、`docs/design/25-unified-deployment.md`、`docs/architecture/PROJECT-FULL-ANALYSIS-REPORT-2026-08.md`
> **数据来源**：仓库源码实读（HEAD `d5e72c7` + 工作区未提交变更，经四路并行探索核实）+ 既有结论交叉验证；置信度标注随文附注
>
> **修订记录**：
>
> | 版本 | 日期 | 修订内容 |
> |------|------|----------|
> | V1.0 | 2026-08-20 | 初稿：本地 TS 轻量运行时 + 平台远程运行时的"能力分级"架构 |
> | V2.0 | 2026-08-20 | 需求澄清重大修订：确立"单机版保障与平台同级的完整智能体能力"，ADR-002/005 被取代（嵌入 Java 引擎 + 桌面 SPI 适配集），"一引擎两形态" |
> | V2.1 | 2026-08-20 | ADR-001 复决：六候选壳层技术评估矩阵，维持 Electron，写死复决触发条件 |
> | V2.2 | 2026-08-20 | §12 细化：变更传播矩阵、三层升级机制、分发渠道与版本协商 |
> | **V3.0** | **2026-09-09** | **平台演进再基线**（基于 S9"会话工作台 zcode 化"及预算/超时/编排/MCP 重构后的平台实况）：① 新增 ADR-009 文件工具对齐策略（复用引擎内置文件工具 + LocalFileWorkspaceSpi）；② 新增 §7.5 本地变更追踪与会话工作台对齐；③ 事件协议扩容（content_reset/plan_*/subagent_status/finishReason/ping 等）；④ 预算行为适配（Token 记账真实化）；⑤ 平台侧任务清单重写（E1/E2/E3 已完成出清单，N4 已实施待验证）；⑥ 共享包与页面复用清单更新（SessionSidebar/PlanCard/FileEditorPanel 族）；⑦ 风险登记册重写 |

---

## 目录

1. [引言](#一引言)
2. [背景与目标](#二背景与目标)
3. [总体架构设计](#三总体架构设计)
4. [关键架构决策（ADR）](#四关键架构决策adr)
5. [进程模型与 IPC 设计](#五进程模型与-ipc-设计)
6. [渲染层设计（UI 复用）](#六渲染层设计ui-复用)
7. [桌面运行时设计（gewu-desktop-runtime）](#七桌面运行时设计gewu-desktop-runtime)
8. [平台接入与协同设计](#八平台接入与协同设计)
9. [能力边界清单](#九能力边界清单)
10. [接口契约设计](#十接口契约设计)
11. [安全架构设计](#十一安全架构设计)
12. [部署与分发设计](#十二部署与分发设计)
13. [非功能需求](#十三非功能需求)
14. [风险评估](#十四风险评估)
15. [实施路线图](#十五实施路线图)
16. [附录](#十六附录)

---

## 一、引言

### 1.1 目的

本文档定义 `gewu-desktop` 模块的完整技术架构。该模块是格物平台的**桌面端智能体产品**，面向两类用户形态：

1. **单机形态**：未使用格物平台的企业或个人，本地安装后即可获得**与平台同级的完整智能体能力**（对话、Agent 定义与管理、技能、本地工具执行、沙箱、多 Agent 编排、子代理派生、任务计划、预算控制、记忆、审计），零平台依赖；
2. **协同形态**：已使用平台的企业团队，桌面端接入集群后**叠加**协同能力（共享模型配置、Agent/技能市场资源、多端会话同步、团队协作、远程审批、云端沙箱回退、配额治理、集中审计）。

两种形态共用同一个智能体引擎与同一套产品能力，差异仅在 SPI 适配层的接线（本地资源 vs 平台资源）。**单机不是能力阉割版，协同不是能力来源**。

### 1.2 V3.0 再基线背景

平台自桌面设计基线（commit `7fc231b`）以来完成约 60 个提交（395 文件，+47k/-20k），其中与桌面端直接相关的三组演进（源码实读核实，置信度：高）：

1. **S9「会话工作台 zcode 化升级」（M0~M4）**：交错式单行流式过程时间线、`plan_task` 任务计划卡片、引擎内置文件工具（read_file/write_file/edit_file/list_dir + FileWorkspaceSpi）、服务端变更追踪与会话工作空间绑定、右侧 Monaco 编辑面板、回合级撤销——**Web 端产品形态与桌面端设计目标（ZCode 类工作台）大幅趋同，UI 与协议复用面显著扩大**；
2. **引擎硬化**：D-2（toolCalls 序列化）、D-10（超时配置化）已修复；预算体系重构（Token 流式真实记账、时间维滚动续期不再熔断、成本维真实计价）；编排引擎 TOOL/ROUTER/PARALLEL/MERGE/PLAN 节点与 pause/resume/cancel 落地（SUBGRAPH 除外）；MCP Streamable HTTP 实现（D-7 解决）；JaCoCo 覆盖率棘轮门禁建立；新增内置子代理（spawn_subagents）；**桌面端 Phase 1 的三个阻塞项全部解除**；
3. **平台拓扑调整**：管理后台拆分（gewu-admin-server :8083 + gewu-admin-web :5002，未提交）、RocketMQ 移除、配额体系引入（UserPreferenceController /quota）、会话分享/归档/置顶/重新生成端点补全、死域清理（Part/SessionInput/SessionContextEpoch 删除）、`scripts/package.sh` + `gewu-ctl.sh` 标准化打包与启停。

据此，V3.0 对受影响章节做再基线：工具体系（ADR-009）、事件协议、预算行为、共享包清单、平台侧任务清单、风险登记册。

### 1.3 术语与缩略语

| 术语 | 定义 |
|------|------|
| 桌面端 / gewu-desktop | 本模块，Electron 桌面应用 |
| 桌面运行时（desktop-runtime） | 新建的 Java 模块：Spring Boot 宿主 + gewu-agent-engine + 桌面 SPI 适配集，以 JVM 子进程形态嵌入桌面端 |
| 一引擎两形态 | 同一个 gewu-agent-engine 分别服务于平台服务端与桌面单机，差异仅在 SPI 适配 |
| 工作区（Workspace） | 用户打开的本地项目目录，会话与工具权限的边界单元 |
| 桌面 SPI 适配集 | desktop-runtime 内对引擎 SPI 的本地化实现集合 |
| 内置文件工具 | 引擎 ReactAgentExecutor 硬编码注册的 read_file/write_file/edit_file/list_dir，经 FileWorkspaceSpi SPI 桥接执行 |
| 引擎 API Profile | desktop-runtime 对外暴露的、与平台 REST/SSE 契约同构的最小端点集 |
| HITL / MCP / SSE | 人机协同审批 / Model Context Protocol / Server-Sent Events |

### 1.4 参考资料

| 资料 | 用途 |
|------|------|
| ZCode（本仓 zcode-rules.md / AGENTS.md） | 本地 Agent 工具的行为对标 |
| gewu-agent-engine 源码（现 ReactAgentExecutor 1791 行，含内置工具/子代理/截断自愈） | **桌面运行时的直接复用对象** |
| `docs/plan/done/s9-workbench-zcode-upgrade-done.md` | S9 交付内容与 web 端 zcode 形态基线 |
| gewu-web 源码（ChatPage 1473 行 / chat.ts 543 行 / SessionSidebar / PlanCard / FileEditorPanel 族） | UI 与协议解析复用来源 |
| git 历史 commit `6def6aa` | Vite 时代 PermissionDialog / TerminalPanel 等参考 |
| 分析报告 §9.3-6 | 桌面端立项决策来源 |

---

## 二、背景与目标

### 2.1 业务背景

格物平台已建成覆盖 SDLC 全生命周期的协作平台，其核心资产 `gewu-agent-engine` 是**刻意零 DB/Web 依赖、SPI + NoOp 默认实现、可独立启动**的认知 Agent 引擎框架。S9 升级后，引擎进一步具备了 ZCode 类工作台的核心要素：内置文件工具与工作空间 SPI、任务计划工具（plan_task）、子代理派生（spawn_subagents）、截断自愈、死循环检测、上下文压缩钩子、真实预算记账。

这一演进强化了 V2 确立的架构判断：**桌面端不需要"再造一个引擎"甚至"再造一套工具"，而是把同一个引擎以 JVM 子进程形态嵌入 Electron，配上本地化的 SPI 适配集**。S9 的内置文件工具走 `FileWorkspaceSpi`——平台侧实现挂的是 docker dev 沙箱，桌面侧实现挂的是本地文件系统——同一个引擎工具集，两种执行后端，UI 与交互协议完全一致。

### 2.2 对标分析（ZCode 能力矩阵 → gewu-desktop 方案，V3.0 更新）

| ZCode 能力 | gewu-desktop 实现（单机形态即具备） | 平台协同形态叠加 |
|------------|--------------------------------------|------------------|
| 本地对话 + 流式思考/工具时间线 | 引擎 ReAct 流式执行 + 复用 web 交错式时间线（S9-M2 形态：单行流式+尾部预览+折叠展开） | 云端会话同源 |
| **文件读/写/编辑（diff 确认）** | **引擎内置 read_file/write_file/edit_file/list_dir + LocalFileWorkspaceSpi（本地文件系统 + PathGuard + 写前 diff 授权）**（ADR-009） | 服务端变更追踪同构 |
| **变更追踪与审查** | 本地 H2 变更表镜像（session_file_change 语义）+ 右侧编辑面板复用（§7.5） | 回合撤销语义对齐 |
| 终端命令执行 | shell 工具（@ToolProvider，命令黑名单 + 授权）+ node-pty 交互终端 | 云端沙箱回退 |
| 项目上下文（AGENTS.md/rules/skills） | 工作区上下文加载器（兼容本仓约定） | 平台技能在线安装 |
| 权限模式（只读/写确认/全自动） | PermissionService 本地实现（三级模式）+ **SPI 内置工具写授权**（§11.1） | 企业策略包下发（P4） |
| MCP 接入 | 引擎 StdioMcpClient + **StreamableHttpClient（2025-03-26 规范，已实现）** | 平台 MCP 配置同步 |
| 技能系统 | 本地技能库 | 技能市场安装/发布 |
| 会话管理 | 本地 H2（含项目分组/归档/置顶/分享预留，S9-M1 语义对齐） | 多端同步、协作会话 |
| 模型接入 | 本地多厂商 Key（SecretVault 托管） | 平台 LLM 代理 + 配额预检（/quota） |
| **任务计划（todo list）** | **引擎 plan_task 内置工具 + PlanCard 组件复用**（S9-M3 同款） | — |
| **子代理（subagent）** | **引擎 spawn_subagents 内置**（maxPerSpawn=5 / maxDepth=2 / 独立线程池防嵌套）+ subagent_status 事件 | — |
| 多 Agent 编排 | 引擎编排四模式 + 自主目标 + **pause/resume/cancel（已真实现）** | 云端编排实例 |

### 2.3 建设目标

| # | 目标 | 衡量指标 | 目标值 |
|---|------|----------|--------|
| G1 | **单机完整智能体能力** | 引擎能力域在单机形态可用率 | 100%（平台固有能力除外，见第九章） |
| G2 | 合理包含 Web 端已实现 AI 能力 | 复用页面/组件覆盖（含 S9 新组件族） | ≥ 70% 组件级复用 |
| G3 | 接入集群协同 | 共享模型、资源同步、会话协同、HITL、云端沙箱、配额 | Phase 3 交付 |
| G4 | 平台侧最小增量 | 平台新增代码 ≤ 1 个 Controller + 1 个代理端点 | 不新建服务 |
| G5 | 可分发可运维 | 三平台安装包 + 签名自动更新 + 引擎进程守护 | Phase 3 交付 |
| G6 | 引零漂移 | 桌面与平台智能体行为一致性 | 同一引擎代码 + 契约测试 |

### 2.4 约束条件（V3.0 更新）

| 类型 | 约束 |
|------|------|
| 团队 | 单人/小团队，Java + TS 技术栈 |
| 既有决策 | Electron 路线（ADR-001 复决维持） |
| 协议 | 桌面运行时对外契约与平台 REST/SSE 同构；**S9 后事件协议已扩容（content_reset/plan_*/subagent_status/finishReason/ping 等），共享包 protocol 按现协议重勘** |
| 引擎前置 | ~~D-2/D-10 修复~~ **已完成**（V3.0 核实）；剩余前置仅：PT-E5 协议 DTO 下沉（范围扩至新事件）、引擎产物发布（降为可选，monorepo 内构建可绕过） |
| 变更基线 | 平台工作区存在大量未提交变更（V47 回合撤销、admin 拆分、预算增量等）——桌面开发以**已提交 HEAD 为基线**，未提交能力（回合撤销 UI 等）标记"待收敛后纳入"，Phase 0 复核 |
| 安全 | 等保 2.0 三级延伸；**内置文件工具绕过 ToolExecutor 五段管线，桌面侧必须在 SPI 实现内自加固**（ADR-009 / §11.1） |
| 体积 | Monaco 自托管资源（S9-M4，~24MB）纳入安装包预算（≤200MB，§13 调整） |

---

## 三、总体架构设计

### 3.1 架构风格

**Electron 三进程桌面应用 + JVM 引擎子进程（一引擎两形态）+ 平台同构 API Profile + 共享 UI 包**（总架构自 V2.0 维持不变，V3.0 演进的是其内部对齐细节）：

1. **同一个引擎，两种部署形态**：`gewu-agent-engine` 在平台侧嵌入 `gewu-interface`（现状），在桌面侧嵌入 `gewu-desktop-runtime`（Spring Boot 宿主 + 桌面 SPI 适配集）；
2. **桌面 SPI 适配集**：把引擎 SPI 接到本地资源——H2 持久化、本地模型 Key、权限弹窗、本地 Docker 沙箱、**本地文件系统（FileWorkspaceSpi）**、本地变更追踪、成本计价；
3. **对外契约与平台同构**（引擎 API Profile），渲染层同一个 api-client 仅切换 baseURL；
4. **渲染层整体复用 gewu-web**：S9 后 web 已是 zcode 形态工作台（交错时间线/计划卡片/文件面板），共享包 `@gewu/agent-ui` 清单相应扩容（§6.1）；
5. **协同形态 = 资源接线切换**。

### 3.2 系统上下文图（C4 Level 1）

（图与 V2.2 一致：本地机器上 Electron 三进程 + JVM 引擎子进程 + 本地资源；协同流量经 gateway :8080；LLM 厂商直连可选。新增两点：① 平台侧管理域已拆分至 admin-server :8083 / admin-web :5002——桌面端协同形态不消费管理域（§9.3）；② 配额体系（/users/me/quota）成为协同形态新交互面。）

### 3.3 桌面端内部架构（C4 Level 2）

```
┌─────────────────────────────────────────────────────────────────────────┐
│ 渲染进程 (React + Vite, sandbox+contextIsolation)                        │
│  App Shell / desktopSlice                                               │
│  页面: chat(交错时间线+PlanCard+SessionSidebar) / terminal / filePanel    │
│        agents / skills / mcp / orchestration / approval / settings / …   │
│  @gewu/agent-ui 共享包: protocol(新事件集) / api-client / process /       │
│                         chat-ui(SessionSidebar·PlanCard·FileEditorPanel族)/ ui / theme │
│  ConnectionManager: 单机 127.0.0.1:port + X-Engine-Token ⇄ 平台 gateway  │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │ preload（类型化 IPC，控制面）
┌──────────────────────────────▼──────────────────────────────────────────┐
│ 主进程: 窗口/托盘/单实例/deep-link/updater │ SecretVault(safeStorage)      │
│         EngineSupervisor(spawn/心跳/守护) │ TerminalManager(node-pty)    │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │ spawn + stdin 引导(令牌/密钥)
┌──────────────────────────────▼──────────────────────────────────────────┐
│ gewu-desktop-runtime (JVM 子进程, 127.0.0.1 随机端口)                     │
│  引擎 API Profile（同构端点集，§10.1，含 file-changes/pin/archive 等）     │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │ gewu-agent-engine（同一份代码，全量）                                │ │
│  │  ReactAgentExecutor(内置: plan_task·spawn_subagents·文件工具·截断自愈· │ │
│  │   死循环检测) │ ToolExecutor+安全链 │ BudgetController(方案A)         │ │
│  │  Orchestration(四模式+生命周期) │ StdioMcpClient+StreamableHttpClient │ │
│  │  HITL │ 记忆SPI │ ContextCompactor钩子                              │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│  桌面 SPI 适配集（V3.0 增粗）:                                           │
│   DesktopPersistenceService(H2) │ DesktopSessionContext                 │
│   DesktopModelProvider(本地Key) │ DesktopProxyLlmProvider(协同)          │
│   DesktopPermissionService │ DesktopHitlGateway(授权弹窗桥)              │
│   LocalFileWorkspaceSpi(本地fs+PathGuard+写授权+变更记录) ★新             │
│   LocalDockerSandboxExecutor │ DesktopMcpConfigSource                   │
│   DesktopAuditService │ DesktopCostCalculator ★新 │ 桌面压缩器(可选) ★新  │
│  存储: H2(会话/消息/变更追踪/审计/用量/kv)                                │
└──────────────────────────────────────────────────────────────────────────┘
```

### 3.4 连接模式

（语义同 V2.2：单机默认完整能力；协同叠加；"云端执行"显式路由。V3.0 补充：协同模式新增**配额预检**（`GET /users/me/quota`）——会话发起前检查套餐余量，配额熔断开关用户可在偏好中控制，与本地 BudgetGuard 叠加生效。）

---

## 四、关键架构决策（ADR）

### ADR-001：桌面壳技术选型 —— Electron（V2.1 复决维持，不变）

市场调研矩阵与复决触发条件见 V2.1 记录（Electron 44 / Tauri 2.11 / Wails 2.14 / Neutralino / JavaFX+JCEF / 原生自绘六候选），结论与退出机制维持不变。

### ADR-002B：嵌入 Java 引擎（一引擎两形态）—— V3.0 前提全部兑现

| 属性 | 内容 |
|------|------|
| 状态 | 已批准（V3.0：原三 Engines 前置修复已由平台完成，决策前提进一步夯实） |
| V3.0 核实 | ① D-2 已修复（`Message.toolCalls` + `LlmRequestBodyBuilder` 序列化，arguments null 兜底 `{}`）；② D-10 已解决（`AgentEngineProperties` 全量配置面：`llm.requestTimeout/streamIdleTimeoutMs`、`budget.*`、`engine.*`、`tool.*`、`subagents.*` 均可配）；③ 引擎测试债大幅缓解（JaCoCo 棘轮：BUNDLE ≥0.45、core/budget/llm/tool/security ≥0.70、orchestration ≥0.40，verify 阶段强制）；④ 产物发布（原 PT-E4）降为可选——desktop-runtime 本就是平台 monorepo 内的 Maven 模块，`mvn -pl gewu-desktop-runtime -am package` 即可构建，仅独立 CI 检出场景需要发布通道 |
| 后果 | 嵌入方案的结构性收益（能力平价、零漂移）在 S9 之后进一步放大：引擎新增的文件工具/子代理/任务计划/截断自愈/死循环检测/编排生命周期**桌面零成本继承** |

### ADR-003：共享包 `@gewu/agent-ui`（V3.0 清单扩容）

维持 V2 决策（pnpm workspace + 共享包，web 渐进迁移）。V3.0 变化：S9 后 web 已完成 Next.js App Router 落位与 API 双轨 A 组收敛（11 模块入 request.ts，B 组 5 模块未收敛），共享包抽离基线更新见 §6.1；**根 workspace 尚未建立**（gewu-web 自持 pnpm-lock），D0-6 不变。

### ADR-004：统一事件协议（V3.0 事件集扩容）

| 属性 | 内容 |
|------|------|
| 状态 | 已批准（V3.0：按现协议重勘） |
| 现行事件集（源码核实） | 基础：`status / thinking / content / tool_call / tool_executing / tool_result / done / error`；S9 新增：**`content_reset`**（截断重试清空正文）、**`plan_created / plan_updated`**（任务计划，含 plan/planTitle）、**`subagent_status`**（子代理进度）、**`done.finishReason`**（stop/length/rounds/loop/budget）、**`done.metadata`**（ExecutionStats，未提交）、**SSE `ping` 帧**（20s 心跳，消费端忽略未知 type）；治理：`budget_warning / budget_exceeded`（现已真实发射）、`confidence_check / verification_result / reflection / experience_saved / failure_recorded`；wenshi 系（`web_* / file`）与编排系（`graph_* / approval_* / EXECUTION_PAUSED/CANCELLED`）维持 |
| 桌面纪律 | 渲染层对未知 type 一律优雅忽略（前向兼容）；`ping` 帧、60s 空闲看门狗、clientId 幂等请求头按 web `consumeChatSse` 同款实现于共享包 api-client |

### ADR-005B：运行时置于 JVM 子进程（维持 V2 决策，不变）

### ADR-006：本地存储 H2 + client_id 幂等同步（V3.0 语义增补）

维持 V2 决策。V3.0 增补：**D-3 已由平台修复**（SessionMessageAppender：MAX(seq)+1 + uk 冲突重试 + messageCount 原子 UPDATE；V33 建 `(session_id, client_id)` 唯一键；命中 clientId 直接返回已落库消息不重复调 LLM）——同步协议的幂等前提已就绪，桌面同步层直接按该语义实现；H2 schema 增补 S9 会话语义五列：`project_id / workspace_id / directory / pinned / time_archived(+status=2 归档)`。

### ADR-007：平台 LLM 代理（维持，不变）

### ADR-008：本地沙箱执行器（V3.0 参数对齐）

维持 docker CLI 封装决策，V3.0 增补**与平台 DockerSandboxProvider 的行为对齐清单**（源码核实）：

| 对齐项 | 平台行为（必须复刻） |
|--------|----------------------|
| 容器常驻 | create 统一 `--entrypoint`/CMD 覆盖为 `tail -f /dev/null`（否则无 CMD 基础镜像启动即退、exec 全 409） |
| 启动幂等 | start 的 304 NotModified 视为成功 |
| 写前建目录 | 写文件前 docker exec `mkdir -p` 父目录（docker cp 不自动建父目录） |
| 资源与安全 | cpu 1 / mem 512MB / disk 1GB / timeout 300s / network=none；no-new-privileges、CapDrop ALL、readonly rootfs（挂卷时关闭）、tmpfs /tmp 64m |
| 卷挂载 | `gewu-ws-{workspaceId前12位}` → 容器 `/workspace`（rw） |
| 自愈 | 探活自愈（记录 running ≠ 容器存活 → 重启；容器被删 → 新建并重绑），对齐 `ensureDevSandboxForUser` 三级自愈语义 |

### ADR-009（V3.0 新增）：文件工具对齐策略 —— 复用引擎内置工具 + LocalFileWorkspaceSpi 自加固

| 属性 | 内容 |
|------|------|
| 状态 | 已批准 |
| 背景 | S9-M4 后引擎已有内置文件工具（read_file/write_file/edit_file/list_dir），经 `FileWorkspaceSpi`（SPI + NoOp 默认，available 时自动注册）桥接执行；平台侧实现为 `SessionFileWorkspaceService`（docker 沙箱后端）。**关键事实（源码核实）**：内置工具在 `ReactAgentExecutor` 内统一分发，**不走 ToolExecutor 五段管线**（无安全链/权限/审计逐调用把关）；平台侧仅靠 SPI 实现内 `sanitizeRelative`（拒绝 `..`）做路径约束，且无写前授权。 |
| 备选方案 | a) **复用内置工具**：桌面实现 `LocalFileWorkspaceSpi`（java.nio + 本地 fs）；b) **弃用内置**（保持 NoOp）：全部文件操作以 @ToolProvider 实现 fs.*（fs.read/write/edit/list/glob/grep），走五段管线全量安全链 |
| 决策 | **方案 a 为主 + SPI 内自加固 + 扩展工具走管线**的混合：① `LocalFileWorkspaceSpi.available()=true`，实现本地文件系统读写（相对工作区根），**SPI 实现内部强制执行**：PathGuard（realpath + 符号链接逃逸检测 + 工作区根约束，强于平台 sanitizeRelative）、写操作写前授权（经 DesktopPermissionService/HitlGateway 弹窗 + diff 预览）、调用级审计记录；② 工具命名/参数/结果文案与平台完全一致（含 `edit_file` 唯一匹配、`+N/-N` 行统计口径），使 AIProcessTimeline 编辑行渲染、ProcessEditDiff 红绿差异块、FileEditorPanel 面板**零适配复用**；③ glob/grep/shell/git/web.search 等引擎未内置的能力以 @ToolProvider 实现，走五段管线全量安全链 |
| 理由 | ① UI/协议/变更追踪三重复用：平台前端正则解析内置工具结果文案（AIProcessTimeline L216），改名即断链；② 变更追踪体系（session_file_change 表 + file-changes API + 回合撤销）以"内置工具写路径"为埋点，绕开即失去 S9 闭环；③ 管线绕过的安全缺口由 SPI 内自加固补齐（桌面恰是唯一有本地文件系统风险的形态，加固必要性强于平台侧）；④ 方案 b 使桌面与平台文件交互体验分叉，违背 G6 |
| 后果 | + 与 web 端 zcode 文件工作流完全同构、组件直用；− SPI 实现承担安全职责（必须以测试钉死：逃逸用例/授权用例/审计用例，见 §10.2 清单）；− glob/grep 仍需自建（引擎未内置，属增量非冲突） |

---

## 五、进程模型与 IPC 设计

（进程清单、主进程↔引擎机制、IPC 通道表与 V2.2 一致；V3.0 增补两点：）

1. **数据面 SSE 消费纪律**：本地引擎与远程平台的流式响应均含 20s `ping` 心跳帧与可能的 `content_reset`/`plan_*`/`subagent_status`/预算事件——共享包 api-client 的 `consumeChatSse` 统一实现「未知 type 忽略 + 60s 空闲看门狗 + clientId 幂等头」，双端同构（对齐 web 现实现）；
2. **IPC 通道增补**：`filePanel:openRequest`（时间线编辑条目 → 打开右侧面板定位）、`turnChanges:undo`（回合撤销，待平台 V47 收敛后启用）——载荷进 protocol/ipc。

---

## 六、渲染层设计（UI 复用）

### 6.1 共享包 `packages/agent-ui`（V3.0 清单更新）

```
packages/agent-ui/
├── protocol/        # 事件类型（ADR-004 现行全集）、Result、错误码、DTO、IPC zod schema、Profile 定义
├── api-client/      # 统一 HTTP（凭据可插拔）+ consumeChatSse（ping/看门狗/clientId/content_reset 分发）
├── process/         # ProcessTracker（含 resetContent/splitFinalContent）+ parseProcessFromMetadata
│                    #   （S9 已落地"过程时间线持久化"——metadata 还原逻辑一并入包）
├── chat-ui/
│   ├── timeline/    # AIProcessTimeline（S9-M2 单行流式形态）+ ProcessEditDiff + lineDiff/diffHunks
│   ├── sidebar/     # SessionSidebar（项目分组/置顶/归档视图，S9-M1）
│   ├── plan/        # PlanCard（任务计划卡片，S9-M3）
│   ├── filepanel/   # FileEditorPanel + MonacoEditor/MonacoDiff（自托管资源打包，S9-M4）
│   └── page/        # ChatPage 抽离产物（见下）+ ChatErrorBanner
├── ui/              # MarkdownRenderer、MarkdownReader、FileCard、Toast、Select 等
└── theme/           # themes + CSS 变量
```

**ChatPage 抽离策略（D0-10 修订）**：ChatPage 现为 1473 行，其中最难抽的是**流式会话状态机**（`streamingMessagesCache` 现场保持、`activeRun` 后台运行轮询、plan/filePanel/回合汇总接线）。抽离分两步：第一步仅抽**纯展示组件族**（timeline/sidebar/plan/filepanel/errorbanner，均为纯 props 或命令对象驱动，已验证解耦）+ lib 层（chat.ts 消费逻辑、agentProcess、sessionFileChanges、chatErrors）；第二步将流式状态机抽为 `useChatRun` hook（页面壳保留）。第一步即可支撑桌面聊天工作台，第二步与 web 迭代并行渐进。

### 6.2 桌面端页面清单与复用映射（V3.0 更新）

| 桌面页面 | 来源 | 数据源绑定 | S9 后变化 |
|----------|------|-----------|-----------|
| 聊天工作台（核心页） | web ChatPage + SessionSidebar + PlanCard + FileEditorPanel | 本地/平台双源 | **形态已是 zcode 工作台**：交错时间线、任务卡片、右侧文件面板、预算/统计提示条；桌面增加：运行时指示器、本地/云端会话标识、@file 引用 |
| 过程时间线 | AIProcessTimeline + ProcessEditDiff | 事件流 | 直迁复用（含 +N/-N 编辑行与红绿差异块） |
| 变更审查/编辑 | FileEditorPanel + Monaco（自托管） | file-changes API（本地 Profile 镜像/平台） | **由新建 react-diff-viewer 改为复用**（省 0.5d 且体验同构）；Monaco 资源打包进安装包 |
| 回合变更条 | TurnChangesBar（**平台未提交**） | /file-changes/turn + /undo | 待平台收敛后纳入（Phase 2 复核） |
| Agent 管理（本地）/ 市场 | AgentManagePage / AgentMarketPage | 本地 / 平台 | 不变 |
| 技能库 / MCP 管理 | SkillLibraryPage / McpServerPage | 本地+平台 | MCP 管理增 streamable_http 类型 |
| 编排工作台 | OrchestrationPage / Canvas | 本地/平台 | **生命周期真实可用**（pause/resume/cancel 已实现） |
| 终端 / 文件树 / 权限弹窗 / 审批中心 / 设置 / 仪表盘 | 同 V2.2 | — | 审批中心增配额提示（协同）；设置增配额开关（协同） |
| 工作区选择器 / 登录连接向导 | 新建 | 本地/平台 | 不变 |

复用度维持 ≥70% 估算（S9 组件族直迁占比上升，ChatPage 壳与 Redux 仍需适配；置信度：中）。

### 6.3 状态管理

沿用 Redux Toolkit；desktopSlice 增补：plan 状态（按会话隔离防串扰，对齐 web `planSessionId` 模式）、filePanel 开合与 openRequest 命令、turnChanges 汇总、配额余量（协同）。

---

## 七、桌面运行时设计（gewu-desktop-runtime）

### 7.1 模块定位与依赖（结构同 V2.2，V3.0 增补 adapter/ 与 endpoint/ 内容）

新增/变更的内部组件：`adapter/LocalFileWorkspaceSpi.java`、`adapter/DesktopCostCalculator.java`、`adapter/DesktopContextCompactor.java`（可选）、`store/` 增 `local_file_change`(+event) 表、`endpoint/` 增 `DesktopFileChangeController.java`。

### 7.2 启动流程与配置（V3.0 更新）

引导与令牌机制不变。**引擎配置面已全面可配**（`AgentEngineProperties`，前缀 `agent.engine`），桌面内置 profile（`application.yml`，工作区 `.gewu/engine.yml` 可覆盖）关键默认值：

```yaml
agent.engine:
  engine: { maxToolRounds: 10, defaultHistoryLimit: 50, loopDetectionEnabled: true,
            roundsRenewEnabled: true, roundsRenewMax: 2 }
  llm: { connectTimeout: 30s, requestTimeout: 120s, streamIdleTimeoutMs: 180s }
  budget:
    tokenBudget: 262144        # 桌面单用户放宽（默认 81920 会真实阻断长对话）
    costBudgetYuan: 0          # 0=仅记账观测；桌面默认不启用金额熔断
    alertThreshold: 0.70 { alertThreshold: 0.70, degradeThreshold: 0.90 }
  subagents: { enabled: true, maxPerSpawn: 5, maxDepth: 2 }   # 子代理单机放开
  tool: { maxOutputSize: 10KB, defaultTimeoutSeconds: 30 }
```

预算行为适配（源码核实的方案 A 语义）：Token 维流式真实记账且可 BLOCK（受 `quotaBlockEnabled` 开关，AgentTask 注入）→ 桌面默认**配额熔断关闭、仅告警**（`quotaTokenBudget` 注入 null 或 blockEnabled=false），失控防线保留在轮次维与死循环检测（不可关闭）；时间维永不熔断（滚动续期）；成本维经 `DesktopCostCalculator` 本地单价表（无表则引擎假单价兜底，仅观测）。

### 7.3 桌面 SPI 适配集（V3.0 更新）

| 引擎 SPI | 桌面实现 | 说明 |
|----------|----------|------|
| PersistenceService / SessionContextService | Desktop*（H2） | schema 含 S9 五列（project_id/workspace_id/directory/pinned/time_archived） |
| LlmProvider | Local + Proxy 双源 | 不变 |
| PermissionService / HitlGateway | Desktop* | 三级模式 + 弹窗桥；**同时被 LocalFileWorkspaceSpi 内部调用**（ADR-009 自加固） |
| **FileWorkspaceSpi** ★ | **LocalFileWorkspaceSpi** | 本地 fs 后端；PathGuard + 写前授权 + diff 预览 + 审计 + **写路径埋变更记录**（§7.5）；相对路径语义与平台一致 |
| SandboxExecutor | LocalDockerSandboxExecutor | ADR-008 行为对齐清单 |
| McpServerConfigSource | DesktopMcpConfigSource | 支持 stdio / **streamable_http**（引擎已实现）/ sse（deprecated，仅兼容） |
| AuditService | DesktopAuditService | 本地哈希链，不变 |
| **CostCalculator** ★ | DesktopCostCalculator | 本地单价表（provider/model→单价）；缺省回退引擎假单价（仅观测） |
| **ContextCompactor** ★ | DesktopContextCompactor（可选） | 引擎 NoOp=放弃压缩；桌面 v1 实现截断式（保留近 N 轮+摘要占位），v2 接本地嵌入摘要 |
| MemoryStore | DesktopMemoryStore | v1 TF-IDF 不变 |
| PolicyService / Trace / Metric | 桌面简化 | 不变 |

### 7.4 本地工具体系（V3.0 重写）

| 层 | 工具 | 执行路径 | 安全 |
|----|------|----------|------|
| 引擎内置（自动注册） | `read_file / write_file / edit_file / list_dir`（相对工作区根；edit 唯一匹配 + `+N/-N` 统计） | FileWorkspaceSpi → **LocalFileWorkspaceSpi**（不走五段管线） | **SPI 内自加固**：PathGuard + 写前授权/diff + 审计 + 变更记录 |
| 引擎内置（自动） | `plan_task`（任务计划）、`spawn_subagents`（子代理派生） | 引擎内部 | 引擎防失控（maxPerSpawn/maxDepth/独立线程池）已自带 |
| @ToolProvider | `fs.glob / fs.grep`（补齐引擎未内置的检索能力） | ToolRegistry → 五段管线 | 全量安全链 + 权限 + 审计 |
| @ToolProvider | `shell.exec`（ProcessBuilder，超时/进程组清理） | 五段管线 | CommandValidator（16 条黑名单共享规则）+ 授权 |
| @ToolProvider | `git.status/diff/log`、`web.search`（SearXNG URL / DDG 可插拔）、`project.context`（AGENTS.md/rules/skills 装载） | 五段管线 | 只读自动放行（PathGuard 内） |
| DB 配置型 | http 工具（协同模式平台配置） | 五段管线 | SsrfValidator 主机白名单 |
| MCP | stdio / streamable_http | 引擎 McpServerManager | MCP 工具首用授权 |

**命名纪律**：文件四工具名与平台逐字一致（UI 正则解析依赖）；扩展工具命名空间 `fs.* / shell.* / git.* / web.*` 不与内置名冲突。

### 7.5 本地变更追踪与会话工作台对齐（V3.0 新增）

对齐平台 S9-M4 的变更闭环，使桌面获得与 web 完全一致的"文件已更改 → 审查 → 编辑 → 撤销"体验：

- **存储**：H2 表 `local_file_change`（镜像 V39 语义：session_id + file_path 唯一键、before_snapshot 首改前基线、change_type CREATE/MODIFY/DELETE、+N/-N）与 `local_file_change_event`（镜像未提交 V47 语义：逐次写入事件 + turn_seq；**以平台收敛后的最终形态为准**，Phase 2 复核）；
- **埋点**：LocalFileWorkspaceSpi 写路径统一记录（唯一写入口，天然全覆盖内置文件工具）；
- **端点**：Profile 增 `/sessions/{id}/file-changes` 系列（列表 /diff /content GET+PUT；/turn+/undo 待收敛）——与平台 `SessionFileChangeController` 同构，FileEditorPanel 直用；
- **diff 口径**：服务端 LCS + unified diff，与前端 `lib/lineDiff.ts` 对齐（平台已有同款实现可直接移植到 desktop-runtime）。

### 7.6 智能体能力在桌面的运行形态（V3.0 更新）

| 能力域 | 单机形态运行方式 |
|--------|------------------|
| ReAct 流式执行 | 引擎原样（**截断自愈**：length 自动加倍重试 ≤3 次 + CONTENT_RESET 清场；**死循环检测**：同工具同参数 3 次注入提示、10 次强制总结） |
| 任务计划 | plan_task 内置 + PlanCard 渲染（plan_created/updated 事件 + `<!--PLAN:-->` 落库回放） |
| 子代理 | spawn_subagents 内置（subagent_status 事件渲染进度） |
| 预算 | 方案 A 四维（§7.2 桌面默认值）；budget_warning/EXCEEDED 事件渲染 |
| 编排 | 四模式 + PLAN 节点 + Kahn 波次并行段 + pause/resume/cancel 真实现（检查点存内存注册表——**桌面单机进程生命周期一致，无需持久化检查点**；重启即放弃，UI 明示） |
| 超时 | 分层空闲超时全链自动（模型 180s 看门狗/工具 30s/连接 2h 帽）；SSE 心跳 |
| MCP / 记忆 / HITL / 审计 | 同 V2.2 |

> 引擎剩余简化项：编排 SUBGRAPH 未实现（按 AGENT 处理）、LlmGoalPlanner 默认关闭——桌面与平台同等存在，随引擎演进自动补齐。

---

## 八、平台接入与协同设计

（接入原则、同步协议与 V2.2 一致；V3.0 更新协同能力矩阵：）

| 协同场景 | 机制 | 平台侧依赖 | 状态 |
|----------|------|-----------|------|
| 会话上行/下行同步 | client_id 幂等（V33 唯一键已就绪）+ S9 五列字段 | PT-N2 | 待做 |
| **配额协同** ★ | 会话前 `GET /users/me/quota` 预检 + 偏好开关（`/users/me/preferences`） | 无改动（UserPreferenceController 已存在） | 复用 |
| 归档/置顶/分享/重新生成 | 归档 unarchive/pin/share/regenerate 端点复用；分享页 `gewu://` 深链 | 无改动（已存在，share 走网关 skip-paths） | 复用 |
| 协作会话 + 远程 HITL | SSE 订阅 + /approvals | **PT-N4 状态：平台 T5.1 已实施 SseBroadcastService 分布式广播（源码见 SseBroadcastService/SseDistributedConfig），桌面侧仅需联调验证** | 验证 |
| 云端执行/沙箱回退/资源导入 | 同 V2.2 | 无改动 | — |
| LLM 代理 / 用量上送 | 同 V2.2 | PT-N1 / PT-N2 | 待做 |

---

## 九、能力边界清单

（判定标准不变：**是否本质依赖服务端状态或多方参与**。V3.0 更新：）

### 9.1 单机完整具备（V3.0 净增）
交错时间线（单行流式形态）、任务计划卡片、**子代理派生**、**内置文件工具 + 本地变更追踪/回合撤销**、截断自愈、死循环检测、编排生命周期（pause/resume/cancel）、MCP Streamable HTTP、上下文压缩钩子。

### 9.2 仅协同形态提供（平台固有）
多用户协作会话、市场发布审核、云端沙箱池、集中审计存证、共享模型配置中心、**配额/套餐治理**（单机仅本地预算）、Wenshi 认知推理引擎（依赖 pgvector+SearXNG，桌面以 web.search 近似）、多副本 HA、**管理后台域**（已拆分至 admin-server/admin-web，桌面明确不承载）。

### 9.3 明确不做
同 V2.2（不内置平台管理后台；项目文档/需求域编辑跳转 Web；无移动端）。

---

## 十、接口契约设计

### 10.1 引擎 API Profile（V3.0 端点集增补）

在 V2.2 集合（chat/chat-stream/models/sessions/agents/skills/mcp-servers/orchestration/approvals/audit/usage/engine-admin）基础上增补：

| 增补端点 | 与平台关系 | 用途 |
|----------|-----------|------|
| `/sessions/{id}/file-changes`（列表//diff//content）+ `/turn` `/undo`★ | 同构 SessionFileChangeController | FileEditorPanel 数据源（★待平台收敛） |
| `PUT /sessions/{id}/archive|unarchive|pin`、`GET /sessions/my?projectId&defaultSpace&status` | 同构 | SessionSidebar 直用 |
| `POST /ai/sessions/{id}/messages/{mid}/regenerate` | 同构 | 消息重发 |
| `GET/PUT /users/me/preferences`、`GET /users/me/quota` | 协同形态转发平台（本地形态返回本地预算语义） | 配额/偏好 |

### 10.2 平台侧任务清单（V3.0 重写——三项已完成出清单）

| # | 状态 | 内容 |
|---|------|------|
| ~~E1（D-2）/ E2（D-10）/ E3（D-3）~~ | ✅ **平台已完成**（源码核实） | 从排期清单移除；桌面侧仅余三厂商回归验证（并入 M1 门禁） |
| E5 | 待做（范围扩大） | 协议 DTO 下沉至 gewu-common/独立模块——现须覆盖 S9 新增事件（content_reset/plan_*/subagent_status/finishReason/metadata）与 FileWorkspaceSpi 位置决策（SPI 在 engine 包内，无需移动） |
| E4 | 降为可选 | 引擎产物 Maven 发布——monorepo 内 `mvn -pl` 构建已可绕过；仅独立 CI 检出需要 |
| ~~N3~~ | ✅ 已完成（V33 + SessionMessageAppender） | 移除 |
| N4 | 🟡 平台已实施（T5.1 SseBroadcastService），桌面联调验证即可 | 验证项 |
| N1 | 待做 | LLM 代理端点（不变） |
| N2 | 待做（字段扩容） | DesktopSync——上行/下行/用量，**须携带 S9 五列 + 变更追踪语义决策**（变更表不同步，仅会话与消息） |
| N9 | 待做 | `/platform/info` 版本协商 |
| V47 收敛 | 平台侧 | 回合撤销（/turn //undo + session_file_change_event）未提交——收敛前桌面 Profile 该组端点标"预留" |

### 10.3 事件协议与 IPC

见 ADR-004 与 §5。Profile 契约测试 fixture 须覆盖新事件（content_reset 清场序列、plan 序列、finishReason 各分支、ping 穿透）。

---

## 十一、安全架构设计

（威胁模型总体同 V2.2；V3.0 关键增补：）

| 威胁 | 缓解措施 |
|------|----------|
| **内置文件工具绕过五段管线的授权缺口** ★ | LocalFileWorkspaceSpi 内自加固三件套（ADR-009）：PathGuard（realpath+逃逸检测，强于平台 sanitizeRelative）、写前授权（PermissionService/HitlGateway 弹窗 + diff 预览）、调用级审计；以专项测试钉死（逃逸/授权拒绝/审计完整性用例进 M1 门禁） |
| 变更追踪快照滥用 | before_snapshot 仅存工作区内文件内容；撤销仅接受快照白名单路径（对齐平台 undo 语义）；审计记录撤销操作 |
| share 端点免鉴权暴露 | 平台已 permitAll `/api/v1/share/**`（脱敏只读）——桌面协同形态分享链接生成走同款端点，桌面侧不缓存他人分享内容 |
| 配额绕过 | 协同形态配额预检在客户端仅为体验优化；真实防线在平台侧（配额表 + 熔断开关），桌面不可也不需要本地执行配额裁决 |

其余（渲染层隔离/临时令牌/密钥托管/注入三重防线/MCP 供应链/更新签名）维持 V2.2。三级权限模式不变；**注意 WORKSPACE_WRITE 模式下的写授权弹窗必须覆盖 SPI 写路径**（ADR-009），清单化验证。

---

## 十二、部署与分发设计

| 项 | V3.0 更新 |
|----|-----------|
| 体积预算 | 安装包 ≤ **200MB**（原 180MB + Monaco 自托管资源 ~10-24MB）；安装后 ≤ 320MB |
| 构建链 | 与平台 `scripts/package.sh` 生态对齐：桌面 runtime jar 由 monorepo `mvn -pl gewu-desktop-runtime -am package` 产出；桌面自有 build.sh/build.ps1（scripts/README 已预留指引位）；manifest 模式参照 package.sh（版本/Git SHA/构建时间） |
| 运维脚本 | 桌面不等价物：EngineSupervisor 即进程守护（gewu-ctl.sh 为平台裸机运维，正交） |
| 平台拓扑 | 管理域拆分（admin-server 8083 / admin-web 5002，未提交）不影响桌面协同面（桌面不消费管理域）；RocketMQ 已移除（部署清单简化） |

其余（jlink/签名/updater/CI/信创）维持 V2.2。

---

## 十三、非功能需求

| 类别 | 指标 | 目标值 | 变化 |
|------|------|--------|------|
| 性能 | 冷启动 → 工作区可用 | ≤ 4s | 不变 |
| | 引擎启动（CDS） | ≤ 2.5s | 不变 |
| | 首 token（直连） | ≤ 1.5s P95 | 不变 |
| 资源 | 空闲内存（JVM -Xmx384m） | ≤ 800MB | 不变 |
| | 安装包 / 安装后 | **≤ 200MB / ≤ 320MB** | ↑（Monaco） |
| 可用性 | 引擎崩溃恢复 | ≤ 3 次自动重启 | 不变 |
| | 流式健壮性 | ping 心跳穿透、空闲看门狗、截断自愈事件正确渲染 | 新增（S9 对齐） |
| 可靠性 | 同步幂等 | client_id 去重零副作用 | 平台 D-3 已修，直接实现 |
| 安全 | 密钥落盘 | 零明文 | 不变 |

---

## 十四、风险评估（V3.0 重写）

| # | 风险 | 等级 | 变化与缓解 |
|---|------|------|-----------|
| R1 | ~~D-2 协议缺陷~~ | ✅ 消除 | 平台已修复（源码核实）；桌面仅余三厂商回归（M1 门禁） |
| R2 | 引擎测试债 | 🟡 降级 | JaCoCo 棘轮门禁已建（core 包 ≥0.70）；桌面按适配层自测 + 契约测试继续兜底 |
| R3 | JVM 体积/启动 | 🟠 维持 | Phase 0 Spike 不变；新增 Monaco 资源体积项（安装包预算已上调 200MB） |
| R4 | Profile 双端漂移 | 🟠 维持 | E5 下沉为强制前置，**范围含 S9 新事件**；契约测试扩 fixture |
| R5 | 单人带宽 | 🟠 维持 | V3.0 净增约 +4~6 人日（文件 SPI/变更追踪/新组件），里程碑顺延 1 周（§15）；裁剪序不变 |
| R6 | LLM 代理负载 | 🟡 维持 | 不变 |
| R7 | Electron CVE | 🟡 维持 | 不变 |
| R8 | 信创 arm64 | 🟡 维持 | 不变 |
| R9 | 引擎简化项感知 | 🟡 维持 | 收窄：SUBGRAPH 与 LlmGoalPlanner 默认关两项 |
| **R10** | **平台工作区未提交变更悬空**（V47 回合撤销、admin 拆分、预算增量、B 组 API 收敛未完成） | 🟠 中（新增） | 桌面以**已提交 HEAD 为基线**开发；依赖未提交能力的任务（TurnChangesBar/undo/配额 UI 细节）标"待收敛"并设 Phase 2 复核点；协议契约测试固定在 HEAD 快照 |
| **R11** | **内置工具管线绕过的安全实现风险** | 🟠 中（新增） | ADR-009 自加固的三件套必须进 M1 安全门禁用例；code review 双人复核 SPI 实现 |
| **R12** | **ChatPage 抽离复杂度超预期**（1473 行含流式状态机） | 🟠 中（新增） | 两步抽离策略（§6.1）：纯组件族先行（已验证解耦），状态机 hook 化渐进；桌面 Phase 1 仅依赖第一步 |

---

## 十五、实施路线图（V3.0：里程碑顺延 1 周）

> 相对周期与裁剪序不变；任务级明细见 `docs/plan/exe_plan/gewu-desktop-implementation-plan-2026-09.md`（V1.1 同步再基线）。

| 阶段 | 内容要点（V3.0 变化加粗） | 估时 | 里程碑 |
|------|--------------------------|------|--------|
| Phase 0 | Spike-A/B（不变）+ 共享包抽离（**protocol 按新事件集重勘；chat-ui 增 SessionSidebar/PlanCard/FileEditorPanel 族/ProcessEditDiff；ChatPage 两步抽离策略第一步**） | 8d | **M0：2026-09-23** |
| Phase 1 | 单机核心：壳层 + runtime MVP（~~E1 阻塞解除~~）+ **LocalFileWorkspaceSpi（自加固）+ 本地变更追踪** + 工具扩展 + 权限/授权 + UI 主链路（**FileEditorPanel 复用替代 react-diff-viewer 新建**） | 27d | **M1：2026-11-06** |
| Phase 2 | 能力补全：沙箱（ADR-008 对齐清单）/ MCP（**streamable_http**）/ **编排生命周期真实化** / Agent 技能管理 / 记忆 v1 / **桌面预算 profile + 配额预检** / 协同基础（N1/N2；**同步携带 S9 五列**） | 20d | **M2：2026-12-04** |
| Phase 3 | 协同完整化（**N4 联调验证**而非等待建设）+ 分发签名更新 + 信创 arm64 | 19d | **M3：2026-12-31** |
| Phase 4 | Backlog 同 V2.2 + **新增：回合撤销完善（随 V47 收敛）** | — | — |

总量：桌面 74 人日（原 70）+ 平台配套约 6 人日（原 15，E1/E2/E3 出清单）。

---

## 十六、附录

### 附录 A：仓库目标结构
（同 V2.2，增补：`gewu-desktop-runtime/.../adapter/LocalFileWorkspaceSpi.java`、`store/V*__local_file_change.sql`、`endpoint/DesktopFileChangeController.java`；共享包 `chat-ui/` 细分 timeline/sidebar/plan/filepanel/page。）

### 附录 B：与既有文档/债务的关联索引（V3.0 更新）

| 关联项 | 现状（2026-09-09 核实） |
|--------|------------------------|
| D-2 toolCalls / D-10 超时 / D-3 幂等 / D-7 MCP / D-16 死域 | ✅ 全部已由平台修复/清理 |
| D-1 引擎测试 | 🟡 JaCoCo 棘轮已建（core ≥0.70），持续 |
| D-19 API 双轨 | 🟡 A 组 11 模块收敛，B 组 5 模块待收敛（桌面共享包不依赖 B 组收敛） |
| R5 编排节点/生命周期 | ✅ 基本补齐（SUBGRAPH 除外），pause/resume/cancel 真实现 |
| S9 会话工作台升级 | ✅ M0~M4 交付；**本设计 §6/§7.4/§7.5 的再基线来源** |
| SSE 多副本（原 N4） | 🟡 T5.1 SseBroadcastService 已实施，桌面联调验证 |
| 回合撤销（V47/TurnChangesBar） | ⏳ 平台未提交，桌面标"待收敛" |
| Vite 时代组件（git 6def6aa） | PermissionDialog/TerminalPanel 参考蓝本（不变） |

### 附录 C：能力矩阵总览（V3.0）

| 能力 | 单机 | 协同 |
|------|:----:|:----:|
| 流式对话/交错时间线/任务计划卡片 | ✅ | ✅ |
| 本地文件工具 + 变更追踪/审查/撤销 | ✅（独有执行后端） | ✅（云端沙箱后端同构） |
| 子代理派生 / 截断自愈 / 死循环检测 | ✅ | ✅ |
| 工具安全链 | 内置工具 SPI 自加固 + 扩展工具五段管线 | ✅ 全量管线 |
| 本地 Docker 沙箱 / MCP(stdio+streamable_http) | ✅ | ✅ + 云端回退/平台清单 |
| Agent/技能库 / 编排（四模式+生命周期） | ✅ | ✅ + 市场安装/发布 |
| 预算四维（方案 A 语义） | ✅（桌面 profile） | ✅ + 平台配额/套餐 |
| 记忆 / HITL / 审计 / 多厂商模型 | ✅ | ✅ + 平台增强 |
| Wenshi 推理 / 协作会话 / 多端同步 / 集中治理 | ❌（web.search 近似） | ✅ |

---

*文档完 | V3.0 要点：平台演进再基线——三项引擎前置已解除、文件工具与 zcode 工作台对齐（ADR-009/§7.5）、事件协议扩容、平台任务清单瘦身（15→6 人日）、里程碑顺延 1 周 | 下一步：① 实施计划 V1.1 同步（已完成，见 exe_plan）② Phase 0 启动（W37：2026-09-14）③ 平台侧确认 E5/N1/N2 排期与 V47 收敛时间*
