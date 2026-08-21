# gewu-desktop 桌面端模块架构设计

> **文档编号**：42 | **版本**：V1.0 | **日期**：2026-08-20
> **设计角色**：资深技术架构师（角色03）
> **状态**：评审中
> **前置阅读**：`docs/design/01-technical-architecture.md`、`docs/design/21-unified-architecture.md`、`docs/design/25-unified-deployment.md`、`docs/architecture/PROJECT-FULL-ANALYSIS-REPORT-2026-08.md`
> **数据来源**：仓库源码实读（commit `7fc231b` 工作区）+ codebase-memory 知识图谱结论交叉验证；置信度标注随文附注

---

## 目录

1. [引言](#一引言)
2. [背景与目标](#二背景与目标)
3. [总体架构设计](#三总体架构设计)
4. [关键架构决策（ADR）](#四关键架构决策adr)
5. [进程模型与 IPC 设计](#五进程模型与-ipc-设计)
6. [渲染层设计（UI 复用）](#六渲染层设计ui-复用)
7. [本地 Agent 运行时设计](#七本地-agent-运行时设计)
8. [平台接入与协同设计](#八平台接入与协同设计)
9. [接口契约设计](#九接口契约设计)
10. [安全架构设计](#十安全架构设计)
11. [部署与分发设计](#十一部署与分发设计)
12. [非功能需求](#十二非功能需求)
13. [风险评估](#十三风险评估)
14. [实施路线图](#十四实施路线图)
15. [附录](#十五附录)

---

## 一、引言

### 1.1 目的

本文档定义 `gewu-desktop` 模块的完整技术架构。该模块是格物平台的**本地开发者 Agent 智能体桌面工具**，实现目标与场景对标 ZCode / Claude Code 一类本地编码智能体：开发者在本地工作区中获得 AI 对话、本地文件操作、终端执行、项目上下文感知等能力，同时可接入格物平台集群获得共享模型配置、Agent/技能生态、团队协作、云端沙箱与审计合规等协同能力。

本设计对齐《项目全面架构分析报告 2026-08》§9.3 第 6 条的立项决策：「复用 gewu-web 渲染层 + 本地沙箱联动是差异化价值」。

### 1.2 范围

- **包含**：桌面端进程架构、本地 Agent 运行时、工具系统与安全链、UI 复用策略、平台接入与协同协议、数据存储、分发与更新、安全设计、实施路线
- **不包含**：平台后端既有功能的重新设计（见 docs/design/01~41）、桌面端视觉规范（另行 UI 设计文档）
- **涉及平台侧增量**：本文档第九章列出为支撑桌面端需在平台侧新增/改造的接口，作为平台侧排期输入

### 1.3 术语与缩略语

| 术语 | 定义 |
|------|------|
| 桌面端 / gewu-desktop | 本模块，Electron 桌面应用 |
| 本地运行时（Local Runtime） | 内嵌于桌面端的轻量 Agent 执行引擎（TypeScript 实现，utilityProcess 中运行） |
| 平台运行时（Remote Runtime） | 集群部署的 gewu-agent-engine 全量引擎（legacy / wenshi 双路由） |
| 双运行时 | 桌面端同时具备本地/平台两个执行后端，按请求路由 |
| 工作区（Workspace） | 用户打开的本地项目目录，会话与工具权限的边界单元 |
| HITL | Human-in-the-Loop，人机协同审批 |
| MCP | Model Context Protocol，工具/资源接入协议 |
| SSE | Server-Sent Events，平台流式推送协议 |
| LLM 代理 | 平台侧新增的 LLM 转发端点，密钥不出服务端 |

### 1.4 参考资料

| 资料 | 用途 |
|------|------|
| ZCode（本仓 zcode-rules.md / AGENTS.md） | 本地 Agent 工具的行为对标（指令优先级、权限模式、技能/规则加载） |
| gewu-agent-engine 源码（144 文件 / 21 SPI） | 本地运行时移植蓝本；事件协议来源 |
| gewu-web 源码（chat.ts / agentProcess.ts / AIProcessTimeline.tsx 等） | UI 与协议解析复用来源 |
| git 历史 commit `6def6aa` | Vite 时代 PermissionDialog / TerminalPanel / FileTreeNode / CodeEditor 组件可恢复 |

---

## 二、背景与目标

### 2.1 业务背景

格物平台已建成覆盖 SDLC 全生命周期的 Web 端协作平台（17 个业务域 / 33 个 Controller / 26 个前端页面），核心 AI 链路（SSE 流式对话 → 工具调用 → HITL → 持久化回放）端到端闭环。但 Web 端受浏览器沙箱限制，**无法触达开发者本地文件系统与终端**——而这正是 ZCode 类工具的核心价值所在。

`gewu-desktop` 在仓库中已预留空目录（electron/、src/），docs/design/01、21、25 均已将「桌面客户端 Electron 28+」列为表现层三端之一。本文档将其立项落地。

### 2.2 对标分析（ZCode 能力矩阵 → gewu-desktop 方案）

| ZCode 能力 | gewu-desktop 实现方式 | 差异化增强 |
|------------|----------------------|-----------|
| 本地对话 + 流式思考/工具时间线 | 复用 ChatPage + AIProcessTimeline + ProcessTracker（web 已实现，对标 zcode 效果） | 双运行时：本地引擎或平台引擎 |
| 文件读/写/编辑（diff 确认） | 本地工具 fs.* + PermissionDialog 授权 + diff 预览 | 平台审计链同步 |
| 终端命令执行 | shell 工具 + node-pty 交互式终端面板 | 命令黑名单校验（移植平台 CommandValidator） |
| 项目上下文（AGENTS.md/rules/skills） | 工作区上下文加载器，兼容本仓既有约定（AGENTS.md + agent-coding-rules/ + agent-sdlc-skills/） | 平台技能库在线安装到本地 |
| 权限模式（只读/写确认/全自动） | 三级权限模式 + 授权弹窗 | 策略可由平台 PolicyService 下发 |
| MCP 接入 | 本地 stdio MCP（@modelcontextprotocol/sdk）+ 平台 MCP 配置同步 | 远程 MCP 服务器管理（平台已实现） |
| 技能系统 | 平台技能库 + 本地技能目录 | 技能市场/审核闭环（平台已实现） |
| 子代理（subagent） | P2：本地运行时多任务派生 | 平台编排引擎（SUPERVISOR/SWARM/DEBATE 四模式）远超单机子代理 |
| 会话管理 | 本地 SQLite + 平台双向同步 | 多端接续（Web/桌面）、团队协作会话 |
| 模型接入 | 本地 API Key 或平台 LLM 代理（密钥不出服务端） | 平台多厂商模型配置 + 国密 SM4 密钥管理 + 预算控制 |

### 2.3 建设目标

| # | 目标 | 衡量指标 | 目标值 |
|---|------|----------|--------|
| G1 | 本地开发者可用类 ZCode Agent 体验 | 本地工作区对话 + 文件/终端工具闭环 | Phase 1 交付 |
| G2 | 合理包含 Web 端已实现 AI 能力 | 复用页面/组件覆盖（聊天、Agent、技能、编排、审批） | ≥ 70% 组件级复用 |
| G3 | 接入集群协同 | 登录网关后可用：共享模型、Agent/技能/MCP 同步、会话协同、HITL、云端沙箱 | Phase 2/3 交付 |
| G4 | 平台侧最小增量 | 平台新增代码控制在 1 个 Controller + 1 个代理端点 + 幂等/广播改造 | 不新建服务 |
| G5 | 可分发可运维 | 三平台安装包 + 签名自动更新 + 崩溃隔离 | Phase 3 交付 |

### 2.4 约束条件

| 类型 | 约束 |
|------|------|
| 团队 | 单人/小团队，Java + TS 技术栈，无 Rust 储备（排除 Tauri 深度定制） |
| 既有决策 | docs/design/01/21 已确定 Electron 28+ 路线 |
| 协议 | 必须复用平台既有 SSE 事件协议与 Result 信封，不另起协议 |
| 安全 | 等保 2.0 三级延伸：本地工具执行需可审计；密钥不得明文落盘 |
| 平台依赖 | 协同能力受平台两个已知债务制约：消息 client_id 幂等未落地（D-3）、SSE 广播为进程内存（多副本失效，§9.3.1） |
| 预算 | 桌面端不捆绑 JVM（安装包体积约束 ≤ 180MB） |

---

## 三、总体架构设计

### 3.1 架构风格

**Electron 三进程桌面应用 + 双运行时（本地轻量引擎 / 平台全量引擎）+ 统一事件协议 + 共享 UI 包**。

核心思路（置信度：高，基于既有资产盘点）：

1. **渲染层整体复用 gewu-web**——web 端全部页面为 `'use client'` 纯客户端组件、Redux 驱动单页，不依赖 Next.js 服务端能力，天然可平移到 Electron 渲染进程；其中 AI 流式链路（chat.ts → ProcessTracker → AIProcessTimeline）是解耦最彻底的部分，直接复用。
2. **本地运行时用 TypeScript 在 Electron utilityProcess 中实现**，而非捆绑 Java agent-engine——ReAct 核心循环可低成本移植（平台 ReactAgentExecutor 662 行），本地工具（fs/child_process/docker）是 Node 原生能力；认知层/编排层/记忆层等重能力**不移植**，留在平台侧，形成「本地 lite / 远程 full」的能力分级。
3. **双运行时共用同一事件协议**（ChatStreamEvent，已核实于 `gewu-application/.../ai/dto/ChatStreamEvent.java`）——渲染层通过 `RuntimeAdapter` 接口无差别消费本地 IPC 事件流或远程 SSE 流，UI 零感知。
4. **平台侧最小增量**——新增 LLM 代理端点与 DesktopSync 同步端点，其余全部复用既有 33 个 Controller 能力。

### 3.2 系统上下文图（C4 Level 1）

```
                       开发者本地机器（不可信边界内）
┌──────────────────────────────────────────────────────────────────┐
│                    gewu-desktop (Electron)                        │
│                                                                  │
│  ┌──────────────┐  ┌──────────────────────┐  ┌────────────────┐ │
│  │ 渲染进程      │  │ 主进程                │  │ utilityProcess │ │
│  │ React UI     │  │ 窗口/托盘/更新/安全存储 │  │ 本地Agent运行时 │ │
│  │ (复用gewu-web)│  │ node-pty终端管理       │  │ (TS ReAct引擎) │ │
│  └──────────────┘  └──────────────────────┘  └────────────────┘ │
│         │                   │                        │           │
│         │  IPC(contextBridge)│            本地工具执行 │           │
│         ▼                   ▼                        ▼           │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ 本地资源: 工作区文件系统 │ 终端(shell) │ 本地Docker(可选) │   │
│  │          本地 stdio MCP 服务器 │ SQLite 会话库 │ safeStorage│   │
│  └──────────────────────────────────────────────────────────┘   │
└───────────────┬──────────────────────────┬──────────────────────┘
                │ HTTPS (JWT Bearer)       │ 本地直连 LLM API
                ▼                          ▼ (用户自有Key, 可选)
┌───────────────────────────────┐   ┌──────────────────┐
│   格物平台集群 (K8s, 2~10副本)  │   │  LLM 厂商 API     │
│  ┌─────────────────────────┐  │   │  DeepSeek/智谱/   │
│  │ gewu-gateway :8080      │  │   │  豆包/千问...     │
│  │ JWT校验/限流/熔断        │  │   └──────────────────┘
│  └───────┬───────────┬─────┘  │
│          ▼           ▼        │      ┌──────────────────┐
│  ┌─────────────┐ ┌──────────┐│      │ Web浏览器端       │
│  │gewu-interface│ │gewu-     ││◄─────│ gewu-web :5001   │
│  │主服务 :8081  │ │sandbox   ││      │ (与桌面端同源会话) │
│  │全量Agent引擎 │ │:8082     ││      └──────────────────┘
│  │33 Controller │ │云沙箱    ││
│  └─────────────┘ └──────────┘│
│  MySQL │ PG/pgvector │        │
│  DragonflyDB │ MinIO │        │
│  SearXNG │ ONNX 嵌入 │        │
└───────────────────────────────┘
```

**外部系统交互说明**：

| 外部系统 | 交互方式 | 协议 | 用途 |
|----------|----------|------|------|
| 格物平台集群 | 同步（REST/SSE） | HTTPS + JWT Bearer | 登录、远程运行时、资源同步、会话协同、HITL、审计上报 |
| LLM 厂商 API | 同步（SSE 流式） | HTTPS + API Key | 本地运行时直连模式（离线于平台时） |
| 本地文件系统/终端 | 本地调用 | Node API | 工作区文件工具、命令执行 |
| 本地 Docker | 本地调用 | Docker Engine API | 本地沙箱（可选，检测可用性） |
| 本地 MCP 服务器 | 子进程 stdio | JSON-RPC | MCP 工具桥接 |

### 3.3 桌面端内部架构（C4 Level 2）

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          gewu-desktop 进程拓扑                           │
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ 渲染进程 (Chromium, sandbox+contextIsolation)                       │  │
│  │                                                                   │  │
│  │  ┌─────────────┐ ┌──────────────┐ ┌────────────────────────────┐ │  │
│  │  │ App Shell   │ │ 页面模块       │ │ Adapters (运行时路由层)     │ │  │
│  │  │ 工作区/命令面板│ │ chat/terminal│ │ ┌────────────────────────┐│ │  │
│  │  │ 侧边栏/Tab   │ │ editor/      │ │ │ RuntimeAdapter (接口)  ││ │  │
│  │  └─────────────┘ │ approval/    │ │ └────────────────────────┘│ │  │
│  │                  │ settings/    │ │   ▲                ▲      │ │  │
│  │  ┌─────────────┐ │ market/...   │ │   │                │      │ │  │
│  │  │ @gewu/agent-ui 共享包          │ │ LocalRuntime   RemoteRuntime│ │  │
│  │  │ ChatPage/AIProcessTimeline/   │ │ Adapter        Adapter   │ │  │
│  │  │ ProcessTracker/api-client/    │ │ (IPC桥)        (HTTP SSE) │ │  │
│  │  │ protocol(事件类型)/theme       │ └────────────────────────────┘ │  │
│  │  └─────────────┘                                               │  │
│  └──────────────────────────┬────────────────────────────────────────┘  │
│                             │ preload (contextBridge 类型化API)          │
│  ┌──────────────────────────▼────────────────────────────────────────┐  │
│  │ 主进程 (Node.js)                                                   │  │
│  │  窗口/托盘/菜单 │ 单实例锁 │ deep-link(gewu://) │ electron-updater │  │
│  │  AuthService(safeStorage+自动刷新) │ ConfigService │ IPC注册中心     │  │
│  │  TerminalManager(node-pty会话池) │ PermissionService(策略仲裁)     │  │
│  └──────────────────────────┬────────────────────────────────────────┘  │
│                             │ MessagePort                               │
│  ┌──────────────────────────▼────────────────────────────────────────┐  │
│  │ 本地Agent运行时 (utilityProcess, 可独立崩溃/重启)                    │  │
│  │                                                                   │  │
│  │  ┌──────────────┐ ┌───────────────┐ ┌──────────────────────────┐ │  │
│  │  │ ReAct执行器   │ │ LLM 客户端     │ │ 工具系统                  │ │  │
│  │  │ (TS移植,循环  │ │ OpenAI兼容    │ │ fs.* / shell / git /     │ │  │
│  │  │  ≤10轮,流式) │ │ 流式+工具调用  │ │ search / mcp.bridge /    │ │  │
│  │  └──────────────┘ │ 双源: 本地Key/ │ │ remote.proxy(平台HTTP工具)│ │  │
│  │  ┌──────────────┐ │ 平台LLM代理   │ └──────────────────────────┘ │  │
│  │  │ 上下文构建器   │ └───────────────┘ ┌──────────────────────────┐ │  │
│  │  │ AGENTS.md/   │ ┌───────────────┐ │ 安全链                    │ │  │
│  │  │ rules/技能    │ │ MCP管理器      │ │ PathGuard/命令黑名单/     │ │  │
│  │  └──────────────┘ │ stdio子进程池  │ │ 注入检测/输出截断          │ │  │
│  │  ┌──────────────┐ └───────────────┘ └──────────────────────────┘ │  │
│  │  │ 事件总线      │ ┌───────────────┐ ┌──────────────────────────┐ │  │
│  │  │ (ChatStream  │ │ 本地存储       │ │ 预算守卫                  │ │  │
│  │  │  Event协议)  │ │ SQLite会话库   │ │ Token/轮次上限            │ │  │
│  │  └──────────────┘ └───────────────┘ └──────────────────────────┘ │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.4 运行模式（路由矩阵）

桌面端会话创建时选择运行时，运行中不可切换（与平台 legacy/wenshi 路由开关语义一致）：

| 模式 | 执行位置 | LLM 密钥来源 | 工具 | 适用场景 |
|------|----------|--------------|------|----------|
| **LOCAL-DIRECT** | 本地运行时 | 用户本地 API Key（safeStorage 加密） | 本地工具 + 本地 MCP | 离线/私有化/敏感代码不出域 |
| **LOCAL-PROXY** | 本地运行时 | 平台 LLM 代理（密钥不出服务端） | 本地工具 + 本地 MCP + 平台 HTTP 工具代理 | 团队共享模型配置 + 本地文件操作（**默认推荐**） |
| **REMOTE** | 平台引擎（网关 :8080） | 平台托管 | 平台全量工具（云端沙箱/HTTP/MCP）+ 预算/认知/记忆/编排 | 重任务、编排、审计强合规、无本地环境 |

**Agent 执行位置启发式默认规则**（用户可覆盖）：Agent 挂载的工具含文件/代码类 → 默认 LOCAL；含编排/云端沙箱 → 默认 REMOTE；普通对话 Agent → LOCAL-PROXY（首字延迟更低）。

---

## 四、关键架构决策（ADR）

### ADR-001：桌面壳技术选型 —— Electron（而非 Tauri）

| 属性 | 内容 |
|------|------|
| 状态 | 已批准 |
| 背景 | 需要跨平台桌面壳 + 本地系统访问（文件/终端/Docker）。docs/design/01、21 已预选 Electron 28+，Tauri 为备选 |
| 决策 | Electron 28+（建议随当前稳定版），配合 electron-vite 构建 |
| 理由 | ① 团队 TS 能力现成，无 Rust 储备（Tauri 后端需 Rust）；② 本地工具/PTY/Docker/子进程管理均为 Node 原生生态（node-pty、dockerode、better-sqlite3）；③ web 渲染层（React）可直接平移；④ 既有设计文档连续性 |
| 后果 | + 开发效率高、生态成熟；− 安装包体积大（~120MB 底座）、内存占用较高、需跟进 Electron CVE 安全更新（缓解见 §10.6） |

### ADR-002：本地运行时采用 TypeScript 轻量实现（而非捆绑 Java agent-engine）

| 属性 | 内容 |
|------|------|
| 状态 | 已批准 |
| 背景 | 三种方案：a) 纯远程瘦客户端（无本地能力，不满足 G1）；b) 捆绑 Java 引擎 jar + JVM（安装包 +150~200MB、双语言进程管理复杂）；c) TS 移植核心循环 |
| 决策 | 方案 c：在 utilityProcess 中实现 **lite 版 ReAct 执行器**（约 2~3k 行 TS），仅移植：ReAct 循环、OpenAI 兼容流式客户端（含工具调用增量累积）、事件发射、简单预算守卫。**不移植**：感知/复杂度路由/双系统路由/Wenshi/记忆向量/编排（留在平台，形成能力分级） |
| 理由 | ① ReAct 核心逻辑紧凑（平台 ReactAgentExecutor 662 行），且已有 OpenAiCompatibleClient 行为蓝本；② Node 对 fs/child_process 是一等公民；③ 避免 JVM 捆绑与跨语言 IPC；④ 引擎侧 21 SPI + NoOp 的设计本就面向服务端嵌入，桌面端不需要其 Spring 装配体系 |
| 后果 | + 体积可控、团队可维护；− **双引擎行为漂移风险**（见 R1 缓解：事件协议契约测试 + 共享 fixture 回放集）；− 本地模式无认知/记忆/编排能力（产品设计上明确标注为「本地轻量模式」） |

### ADR-003：UI 复用策略 —— 抽离共享包 `@gewu/agent-ui`（绞杀者式，不重构 web 现状）

| 属性 | 内容 |
|------|------|
| 状态 | 已批准 |
| 背景 | gewu-web 为 Next.js 14「伪多页、实为 Redux 单页」纯客户端应用；API 封装双轨（axios request.ts vs 20+ 模块私有 authFetch）是既有技术债（D-19） |
| 决策 | ① 仓库根引入 **pnpm workspace**（gewu-web 已有 pnpm-lock.yaml），新建 `packages/agent-ui` 共享包：`protocol`（事件类型/DTO/Result 信封）、`api-client`（统一 HTTP+SSE 客户端）、`chat-ui`（ChatPage 核心、AIProcessTimeline、MarkdownRenderer、FileCard、Toast 等）、`process`（ProcessTracker）、`theme`；② gewu-web **渐进**迁移引用（不阻塞其现有迭代），gewu-desktop 从第一天直接消费；③ 桌面渲染层用 Vite（不需要 Next.js SSR） |
| 理由 | ① 避免 105 文件悬空变更期间对 web 再做激进重构；② 共享包同时偿付 D-19 API 双轨债（新 api-client 成为唯一规范实现）；③ AI 流式链路（chat.ts/agentProcess.ts）已被验证为解耦最彻底的部分，抽离成本低 |
| 后果 | + 单一实现双端消费；− 过渡期双轨并存（设 2 个月收敛期限，与平台 R8 双轨收敛时间表对齐）；− ChatPage 550 行大组件需在抽离时拆分（聊天气泡独立成组件） |

### ADR-004：统一事件协议 —— 双运行时共用 ChatStreamEvent

| 属性 | 内容 |
|------|------|
| 状态 | 已批准 |
| 背景 | 平台 SSE 协议已定型：`data: {json}\n\n` 帧、`[DONE]` 哨兵、事件体 `ChatStreamEvent{type, content, reasoning, toolCall, toolResult, webSearch, verify, file, errorMessage}`，事件类型 13+ 种（status/thinking/content/tool_call/tool_executing/tool_result/web_*/file/done/error）；前端 ProcessTracker 已按此协议累积时间线 |
| 决策 | 本地运行时的事件总线**逐字节复用**该协议（JSON 字段与事件类型完全一致）；渲染层 `RuntimeAdapter` 接口统一签名 `chatStream(req, callbacks): AbortController`，LocalRuntimeAdapter 走 IPC、RemoteRuntimeAdapter 走 HTTP SSE（复用共享包 chat.ts 解析器） |
| 理由 | UI 组件（AIProcessTimeline/ProcessTracker）零改动复用；协议演进由平台侧单点定义（协议常量置于 `@gewu/agent-ui/protocol`，与 Java 侧 ChatStreamEvent 字段对齐校验） |
| 后果 | + 双运行时对 UI 透明；− 协议变更需三端同步（Java DTO / 共享包类型 / 本地运行时发射器）——通过契约测试固化 |

### ADR-005：本地运行时置于 utilityProcess（而非主进程）

| 属性 | 内容 |
|------|------|
| 状态 | 已批准 |
| 决策 | 运行时以 Electron `utilityProcess` 运行（MessagePort 通信），主进程仅做注册/转发；运行时崩溃自动重启并续传会话状态（SQLite 落盘） |
| 理由 | ① 崩溃隔离（LLM/工具死循环不带走窗口）；② 主进程保持轻量，避免阻塞窗口管理/更新；③ node-pty 终端会话池因需要主进程生命周期绑定而留在主进程，与运行时通过 IPC 解耦 |
| 后果 | + 稳定性；− 多一层 MessagePort 转发（延迟可忽略，本地进程间通信） |

### ADR-006：本地存储 SQLite + client_id 幂等同步（与平台 D-3 债务修复联动）

| 属性 | 内容 |
|------|------|
| 状态 | 已批准 |
| 决策 | ① 本地会话/消息用 better-sqlite3，表结构与平台 `session`/`session_message` 字段对齐（含 client_id、seq）；② 同步上行以 client_id 幂等（平台侧需落地 D-3 修复：SendMessageCommand 增 clientId + 查重）；③ 增量下行按 `updated_at` 水位 |
| 理由 | 桌面端是 client_id 幂等的**第一个真实消费方**，为平台 D-3 修复提供直接业务动因；字段对齐使同步层退化为纯搬运，无需双向映射 |
| 后果 | + 同步简单可靠；− 依赖平台 D-3 修复完成（Phase 2 前置条件，见 R2） |

### ADR-007：平台 LLM 代理 —— 密钥不出服务端

| 属性 | 内容 |
|------|------|
| 状态 | 已批准 |
| 决策 | 平台新增 `POST /api/v1/ai/llm-proxy/chat/stream`：桌面本地运行时将 messages/tools 发给代理，代理从模型配置（SM4 解密 Key）调用厂商，以 SSE 回传**归一化 LlmChunk**（delta/reasoning/toolCallDelta/finishReason/usage），并服务端记账用量/预算 |
| 理由 | ① 团队场景下用户不应各自管理 API Key（平台已有多厂商配置 + 国密密钥管理 + 预算控制资产）；② 用量闭环：桌面消耗计入平台统计（UsagePage/成本治理）；③ LOCAL-DIRECT（本地 Key）保留为离线/私有化兜底 |
| 后果 | + 密钥集中管控、用量统一；− 平台新增流量负载（复用网关限流 100 次/60s + 服务端预算检查缓解，见 R6）；− 代理端点需要防滥用（JWT + 每用户预算上限） |

---

## 五、进程模型与 IPC 设计

### 5.1 进程清单

| 进程 | 技术 | 职责 | 生命周期 |
|------|------|------|----------|
| 主进程 | Node.js (Electron Main) | 窗口/托盘/菜单、单实例锁、deep-link（gewu://）、自动更新、safeStorage 凭据、AuthService（token 自动刷新）、ConfigService、PermissionService（权限策略仲裁）、TerminalManager（node-pty 会话池）、IPC 注册中心 | 应用级 |
| 渲染进程 | Chromium (React + Vite) | 全部 UI；`sandbox: true`、`nodeIntegration: false`、`contextIsolation: true`；经 preload 暴露的类型化 API 访问能力 | 窗口级（可多窗口） |
| 本地运行时 | utilityProcess (Node.js) | ReAct 执行器、LLM 客户端、工具执行、安全链、MCP stdio 子进程池、SQLite | 会话级（崩溃自动重启 ≤3 次） |
| MCP 子进程 | stdio JSON-RPC | 本地 MCP 服务器（由运行时管理） | 按需 |

### 5.2 IPC 通道契约（主进程 ↔ 渲染进程，preload 类型化）

| 通道 | 方向 | 载荷 | 说明 |
|------|------|------|------|
| `chat:stream:start` | R→M | `{sessionId, runtime, request: ChatRequest}` | 发起对话；runtime ∈ local/remote |
| `chat:stream:event` | M→R | `ChatStreamEvent` | 逐事件推送（与平台 SSE 同构） |
| `chat:stream:abort` | R→M | `{sessionId}` | 中断（本地 dispose / 远程 AbortController） |
| `tool:permission:request` | M→R | `{id, tool, action, target, diff?}` | 工具授权弹窗（含写操作 diff 预览） |
| `tool:permission:respond` | R→M | `{id, decision: allow/deny/alwaysAllow}` | alwaysAllow 记入会话级白名单 |
| `session:crud` | R→M | 标准 CRUD 参数 | 本地 SQLite 会话管理 |
| `sync:push` / `sync:pull` | R→M | `{sessionId}` / `{since}` | 平台同步触发 |
| `terminal:create/write/resize/exit` | R↔M | `{tabId, data, cols, rows}` | xterm.js ↔ node-pty |
| `auth:login/logout/status` | R→M | `{gatewayUrl, username, password}` | 主进程持有 token（渲染层不可见） |
| `workspace:open/recent/list` | R→M | `{path}` | 工作区管理（含 AGENTS.md 检测） |
| `mcp:server:manage` | R→M | MCP Server 配置 CRUD | 本地 MCP 管理 |
| `app:config:get/set` | R↔M | 配置键值 | 设置持久化 |

**契约保障**：通道常量与载荷类型定义于 `@gewu/agent-ui/protocol`，preload 导出的 API 以 TypeScript 接口约束，载荷经 zod 校验（防渲染层被注入后伪造 IPC——渲染层是安全边界，见 §10.1）。

### 5.3 主进程 ↔ 运行时（MessagePort）

- 主进程 `postMessage` 转发 `chat:stream:start` 至运行时，运行时回推事件流；
- 权限请求由运行时发起 → 主进程 PermissionService 仲裁（策略命中自动放行/拒绝，否则转发渲染层弹窗）；
- 心跳：运行时每 30s 上报健康度（内存/进行中任务数），失联 >10s 判定崩溃并重启。

---

## 六、渲染层设计（UI 复用）

### 6.1 共享包 `packages/agent-ui` 结构

```
packages/agent-ui/
├── protocol/            # ChatStreamEvent 类型、事件常量、Result 信封、错误码枚举、DTO
├── api-client/          # 统一 HTTP 客户端（Bearer 注入/刷新/Result 解包）+ SSE 解析器（自 chat.ts 抽离）
├── process/             # ProcessTracker、createProcessStreamHandler、toolHeadline（纯 TS，零依赖）
├── chat-ui/             # ChatPage（抽离时拆分：SessionList/Composer/MessageBubble/ModelPicker/AgentPicker）
│   └── timeline/        # AIProcessTimeline
├── ui/                  # MarkdownRenderer、FileCard、Toast、Select、Card 等
└── theme/               # themes.ts + CSS 变量（多主题，复用 web 现有实现）
```

### 6.2 桌面端页面清单与复用映射

| 桌面页面 | 来源 | 复用方式 | 差异点 |
|----------|------|----------|--------|
| 工作区选择器（启动页） | 新建 | — | 最近工作区、新建/打开目录、AGENTS.md 检测标识 |
| 聊天工作台（核心页） | web ChatPage + ChatHomeView | 共享包直接复用 | ① 注入 RuntimeAdapter 与运行时切换器；② 会话列表增加「本地/云端」标识；③ Composer 增加文件引用（@file）与截图粘贴 |
| 过程时间线 | web AIProcessTimeline | 直接复用 | 无 |
| 终端面板 | Vite 时代 TerminalPanel（git 6def6aa）+ xterm.js | 重写（原实现为无 PTY 的演示品） | xterm.js + node-pty，多 Tab，工具命令执行可「发送到终端」复现 |
| 文件树 | Vite 时代 FileTreeNode（git 6def6aa） | 参考重写 | 虚拟滚动、git 状态标记 |
| Diff/代码查看 | 新建（react-diff-viewer 起步） | — | Phase 1 只读 diff；Phase 4 Monaco 只读浏览 |
| 权限弹窗 | Vite 时代 PermissionDialog（git 6def6aa） | 参考重写 | 增加 diff 预览、alwaysAllow、会话白名单管理 |
| 审批中心（HITL） | web AuditCenterPage 部分逻辑 | 复用 + 扩展 | 合并「本地工具授权历史」与「远程 HITL 待办」（订阅 approval_required SSE） |
| Agent 市场/我的 Agent | web AgentMarketPage / MyAgentsPage / AgentManagePage | 共享包复用 | 增加「在本地运行」入口与工具本地可用性检测 |
| 技能库 | web SkillLibraryPage / MySkillsPage | 复用 | 增加「安装到本地工作区」（写入 .gewu/skills/） |
| MCP 管理 | web McpServerPage | 复用 | 分「平台 MCP（远程）」与「本地 stdio MCP」两个 Tab |
| 编排/工作流 | web OrchestrationPage / WorkflowPage / Canvas | 复用（远程模式） | Phase 3 接入；本地运行时不支持编排（引导切远程） |
| 设置 | web SettingsPage 骨架 | 复用 + 扩展 | 增加：平台连接（gateway URL/登录态）、模型源（本地 Key/平台代理）、权限模式、同步策略、更新通道 |
| 仪表盘 | web UsagePage / DashboardPage | 复用 | 增加「本地会话用量统计」（SQLite 聚合） |
| 登录/连接向导 | web LoginPage 参考重写 | — | 桌面形态：gateway 地址 → 账号密码/OAuth → 设备命名；游客模式（LOCAL-DIRECT）可跳过 |

**复用度估算**：13/16 页面以共享包或参考重写覆盖，组件级复用 ≥ 70%（G2 达标；置信度：中，抽离 ChatPage 时的大组件拆分存在工作量不确定性）。

### 6.3 状态管理

- 沿用 Redux Toolkit（与共享组件配套）；新增 `desktopSlice`：工作区列表、当前运行时偏好、连接状态、同步水位、权限白名单；
- 会话运行态（消息流/时间线快照）仍置于页面级 useState（与 web 一致），持久化由本地运行时 SQLite 承担。

---

## 七、本地 Agent 运行时设计

### 7.1 执行器（ReactExecutor，TS 移植）

移植自平台 `ReactAgentExecutor` 的**核心子集**（行为对齐，配置同名）：

```
executeStream(task):
 1. 安全检查：InjectionDetector.checkInput(message)   // 移植平台正则(8高危+5中危)
 2. 上下文构建：ContextBuilder.build(sessionId, workspace)
    ├─ 系统提示词（平台 AgentSpec.systemPrompt / 本地默认）
    ├─ 工作区上下文：AGENTS.md → rules/*.md → .gewu/skills/*.md（本仓约定同源）
    └─ 历史消息（SQLite 取最近 N 条，默认 50，与平台 defaultHistoryLimit 对齐）
 3. ReAct 循环（round ≤ maxToolRounds=10）：
    ├─ BudgetGuard.check(token/轮次)                // 超限 -> budget_exceeded 事件
    ├─ llmClient.chatStream(request)                // OpenAI 兼容 SSE
    │    chunk 分派: reasoning->thinking事件 / delta->content / toolCallDelta->累积
    ├─ 无工具调用 -> 输出截断 -> done 事件 -> 持久化 -> 结束
    └─ 有工具调用 -> tool_call事件 -> PermissionService 仲裁(阻塞等待授权)
         -> tool_executing -> 执行(并行 Promise.all, ≤4 并发) -> tool_result -> 回灌 -> 下一轮
 4. 异常: ERROR 事件(不向订阅者抛异常, 对齐平台 onErrorResume 语义)
```

**刻意不移植**（能力分级，UI 明示）：PerceptionEngine/ComplexityRouter/DualSystemRouter/BudgetController 四维预算/记忆向量/编排/Wenshi 推理。本地仅保留简化 BudgetGuard（会话级 token 上限 + 轮次上限，防失控）。

**与 Java 引擎行为一致性**：平台 PoC 题集回放——同一组（messages, tools, 固定 seed 温度 0）输入，断言两侧事件序列骨架一致（契约测试，见 R1 缓解）。

### 7.2 LLM 客户端（双源 ProviderRegistry）

| 源 | 密钥位置 | 解析优先级 |
|----|----------|-----------|
| 平台代理 | 服务端（SM4），桌面仅持 JWT | 默认（已登录且代理可用） |
| 本地直连 | safeStorage 加密的用户 API Key | 兜底/离线/私有化 |

实现要点（移植平台 OpenAiCompatibleClient 行为）：强制 HTTP/1.1、reasoning_content 与 content 分离、流式 function calling 增量累积（ToolCallAccumulator 模式）、usage 统计回传 BudgetGuard；**修复平台已知缺陷 D-2/D-11 在 TS 侧的等价实现**（Message 模型带 toolCalls 字段、content 为空不回退 reasoning 当答案）——TS 侧不复制已知 bug。

### 7.3 本地工具系统

工具接口对齐平台 `Tool`（getDefinition + invoke），注册表按「内置 → 工作区（.gewu/tools/）→ MCP 桥接 → 远程代理」优先级解析：

| 工具 | 能力 | 安全约束 |
|------|------|----------|
| `fs.read` | 读文件（文本/截断 10KB，对齐平台 maxOutputSize） | PathGuard：限制工作区内 |
| `fs.write` | 写文件 | 授权弹窗 + diff 预览 |
| `fs.edit` | 精确字符串替换（对齐 ZCode Edit 语义：唯一匹配校验） | 授权弹窗 + diff 预览 |
| `fs.list / fs.glob / fs.grep` | 目录树/模式匹配/内容检索 | 只读自动放行（PathGuard 内） |
| `shell.exec` | 命令执行（超时/工作目录/环境变量白名单） | 命令黑名单（移植平台 CommandValidator 16 条）+ 授权弹窗（只读命令白名单自动放行） |
| `git.status/diff/log` | 仓库状态（isomorphic-git 或 shell 封装） | 只读 |
| `project.context` | 返回 AGENTS.md/rules 摘要 | 只读 |
| `mcp.call` | 本地 stdio MCP 工具转发 | MCP 工具粒度授权（首用授权） |
| `remote.tool` | 平台 HTTP 工具代理（经网关转发，复用平台 SSRF/审计链） | 平台侧权限评估 |

### 7.4 安全链（本地纵深，平台六层链的子集）

```
输入: InjectionDetector（移植正则，高危拦截/中危告警）
  ↓
工具执行管线:
  ① PathGuard.check      // realpath 解析 + 符号链接逃逸检测 + 工作区根约束
  ② CommandValidator     // 16 条黑名单 + 危险 sudo/rm -rf 模式（TS 移植）
  ③ PermissionService    // 三级模式仲裁（见 §10.3）
  ④ 执行（超时/输出截断 10KB/进程组清理）
  ⑤ 本地审计记录（SQLite local_audit 表）
  ↓
输出: 输出截断 + PII 脱敏（手机/邮箱/身份证，移植平台 OutputSanitizer 正则）
```

### 7.5 MCP 支持

- **本地 stdio**：`@modelcontextprotocol/sdk` 客户端，配置存储于工作区 `.gewu/mcp.json`（格式兼容主流工具），运行时管理子进程池（空闲 10min 回收）；
- **平台 MCP 同步**：拉取平台 `/mcp-servers` 配置，transport=stdio 的服务器可「一键本地化」（在本地拉起同配置子进程）；sse/streamable_http 的走远程运行时；
- 复用平台 MCP 服务器管理器语义（连接缓存/协议版本 2024-11-05）。

### 7.6 本地存储（better-sqlite3）

| 表 | 关键字段 | 与平台关系 |
|----|----------|-----------|
| `local_session` | id/agent_id/runtime/mode/model/workspace_path/同步水位 | 字段对齐 `session`（映射上传） |
| `local_message` | session_id/seq/client_id/role/content/metadata/timestamp/dirty | 字段对齐 `session_message`；client_id 幂等同步键 |
| `local_audit` | tool/action/target/decision/operator/timestamp | 可选同步至平台审计链 |
| `local_usage` | session_id/provider/model/prompt_tokens/completion_tokens/cost | 本地仪表盘 + 用量上报 |
| `kv_store` | 权限白名单/同步水位/运行时偏好 | — |

WAL 模式；库文件位于 `userData/gewu-desktop.db`；敏感字段（本地 API Key）不入库，走 safeStorage。

---

## 八、平台接入与协同设计

### 8.1 接入拓扑与认证

- **统一入口**：桌面端所有平台流量经 **gewu-gateway :8080**（JWT 校验 → 限流 100 次/60s → 熔断 → 转发 8081/8082），与 Web 端完全同构，不新开端口/协议；
- **认证复用**：`POST /api/v1/auth/login`（HS256 JWT，access 30min / refresh 7d）；token 由主进程 AuthService 持有（safeStorage 加密存储），过期前 5min 自动 refresh，渲染层仅见会话态不触 token；
- **设备标识**：首次登录生成设备 ID（UUID + 设备命名），登录请求头携带 `X-Device-Id`，用于平台侧会话多端管理与审计（Phase 2 平台侧记录，不改认证协议）。

### 8.2 协同能力矩阵

| 协同场景 | 机制 | 平台侧依赖 | 阶段 |
|----------|------|-----------|------|
| 共享模型配置 | LLM 代理（ADR-007） | 新增代理端点 | P2 |
| Agent/技能/MCP 同步 | 复用 `/agents` `/skills` `/mcp-servers` 列表端点 + 本地缓存（ETag） | 无改动 | P2 |
| 会话上行（本地→平台） | 批量同步 local_message（client_id 幂等） | **D-3 修复** + 新增同步端点 | P2 |
| 会话下行（平台→本地） | 增量拉取（updated_at 水位），只读或「接续为本地会话」 | 新增同步端点 | P2 |
| 团队协作会话 | 订阅既有 `GET /api/v1/sse/sessions/{id}`（message/approval_required 命名事件）+ 发消息 API | **SSE 广播 Redis Pub/Sub 化**（多副本前置） | P3 |
| HITL 远程审批 | 订阅 approval_required + 既有 `/approvals` 端点批准/驳回 | 同上 | P3 |
| 云端沙箱回退 | 本地 Docker 不可用时走 `/sandboxes`（经网关路由至 :8082） | 无改动 | P3 |
| 执行记录上报 | 复用 `/agents/executions` create/complete/fail | 无改动 | P2 |
| 用量上报 | 代理模式服务端自动记账；直连模式批量上报 `local_usage` | 新增轻量上报端点（或并入同步端点） | P2 |
| 审计协同 | local_audit 批量上传至平台审计链（SHA-256 哈希链已就绪） | 审计接口扩展 | P4 |

### 8.3 会话同步协议（上行示例）

```
桌面(本地运行时)                    平台(DesktopSyncController)
    │ POST /api/v1/desktop/sync/sessions        │
    │ {sessionId, messages:[{clientId, seq,     │
    │   role, content, metadata}], watermark}   │
    ├──────────────────────────────────────────►│
    │                                           │ 幂等: client_id 查重落库
    │                                           │ seq 冲突: 平侧重排
    │◄──────────────────────────────────────────┤
    │ {code:10000, data:{accepted, skipped,     │
    │  nextWatermark}}                          │
```

冲突策略：消息级 client_id 幂等去重；会话元信息（标题/Agent 绑定）LWW（last-write-wins，以 updated_at 精确到毫秒判定）；不做字段级合并（预留平台 parentId 分叉机制为后续「会话分支」能力）。

### 8.4 与 Web 端的多端接续

同一账号在 Web 与桌面端看到同一份云端会话（`/ai/sessions` 同源）；桌面本地会话标记「本地」，可显式「发布到云端」进入协作域。远期（P4）：会话在设备间移交（设备在线状态 + gewu:// 深链唤起）。

---

## 九、接口契约设计

### 9.1 平台侧新增/改造清单（输入给平台排期）

| # | 类型 | 端点/变更 | 说明 |
|---|------|-----------|------|
| N1 | 新增 | `POST /api/v1/ai/llm-proxy/chat/stream` | LLM 代理：入参 `{provider, model, messages, tools, stream, clientRequestId}`；出参 SSE `data:{LlmChunk}\n\n`（delta/reasoning/toolCallDelta/finishReason/usage）；JWT 鉴权 + 网关限流 + 服务端预算检查 + usage 记账；错误码走 Result 信封（SSE error 事件） |
| N2 | 新增 | `POST /api/v1/desktop/sync/sessions`（上行）、`GET /api/v1/desktop/sync/sessions?since=`（下行） | 会话批量同步；**前置：D-3 client_id 幂等修复** |
| N3 | 改造 | `session_message` 写路径 clientId 查重 | 即平台 P0 债务 D-3，桌面端为第一消费方 |
| N4 | 改造 | SseEventManager → Redis Pub/Sub（DragonflyDB 已就绪） | 多副本 SSE 广播（平台 §9.3.1 前置条件），P3 协作/HITL 依赖 |
| N5 | 复用 | `/auth/*`、`/ai/chat/stream`、`/ai/sessions`、`/agents/*`、`/skills/*`、`/mcp-servers/*`、`/approvals/*`、`/sse/sessions/{id}`、`/sandboxes/*`、`/agents/executions/*` | 零改动 |
| N6 | 可选 | `GET /api/v1/desktop/resources/manifest` | agents/skills/mcp 聚合快照 + ETag（免多端点轮询；P2 可先用现有列表端点代替） |
| N7 | 可选 | 错误码域 18xxx 分配给 desktop sync | 遵循 ResultCode 5 位编码规范 `[域2位][模块2位][序号1位]` |

### 9.2 事件协议（复用，不新增类型）

本地运行时发射的事件类型 = 平台 `ChatStreamEvent` 子集：

`status / thinking / content / tool_call / tool_executing / tool_result / done / error / budget_exceeded`

远程运行时额外透传：`web_search_* / web_verifying / web_verdict / file / graph_* / approval_*`（平台已定义）。新增类型一律由平台侧 Java DTO 先行定义 → 共享包 protocol 跟随（ADR-004 三端同步规则）。

### 9.3 IPC 契约

见 §5.2 通道表；全部载荷 schema 以 zod 定义并生成 JSON Schema 存于 `packages/agent-ui/protocol/ipc`，作为渲染层与主进程的共同契约基线。

---

## 十、安全架构设计

### 10.1 安全边界与威胁模型（桌面端特有）

| 威胁 | 缓解措施 |
|------|----------|
| 渲染层 XSS → 伪造 IPC 调用工具 | 渲染进程 sandbox + contextIsolation；preload 仅暴露白名单 API；IPC 载荷 zod 校验 + 主进程侧权限仲裁（渲染层永远不是权限判定者） |
| 提示注入诱导危险工具调用 | InjectionDetector（移植平台正则）+ PathGuard + 命令黑名单 + 授权弹窗三重防线；写操作强制 diff 预览 |
| 工作区路径逃逸（符号链接） | PathGuard 用 realpath 解析后二次校验前缀，拒绝工作区外一切路径 |
| 本地密钥泄露 | safeStorage（OS keychain）加密；不入 SQLite/日志；剪贴板自动清空可选项 |
| 平台 JWT 被本地进程窃取 | token 仅存主进程内存 + safeStorage；渲染层经 IPC 代理请求（不落 localStorage，与 web 差异点） |
| 自动更新投毒 | electron-updater + electron-builder 签名校验（latest.yml 哈希）；仅 HTTPS 更新源 |
| MCP 服务器供应链风险 | 本地 MCP 安装需用户确认命令行；MCP 工具首用授权；来源记录入 local_audit |
| 敏感代码外泄 | LOCAL-DIRECT/私有化模式代码与提示词不出本机；代理模式传输 TLS 加密且可按项目禁用代理（项目级开关） |

### 10.2 权限三级模式（对标 ZCode）

| 模式 | 只读工具 | 工作区写/命令白名单外 | 黑名单命令 |
|------|----------|----------------------|-----------|
| READ_ONLY（默认） | 自动 | 一律弹窗 | 一律拒绝 |
| WORKSPACE_WRITE | 自动 | 弹窗 + alwaysAllow 会话白名单 | 一律拒绝 |
| FULL_AUTO | 自动 | 自动（审计记录） | 拒绝 + 告警 |

策略可由平台 PolicyService 下发的策略包覆盖（企业管控场景，P3）。

### 10.3 审计

本地全量工具调用入 `local_audit`；P4 批量上送平台审计链（复用既有 SHA-256 哈希链防篡改机制，链头校验在平台侧完成）。

### 10.4 合规要点（等保 2.0 三级延伸）

- 传输：全链路 TLS（仅允许 https:// 网关地址，自签证书需显式导入并告警）；
- 数据分类：工作区代码默认「内部」级——代理模式上传内容仅为提示词必需片段，文件工具输出截断 10KB；
- 日志脱敏：本地日志 PII 脱敏（复用平台 OutputSanitizer 正则）。

---

## 十一、部署与分发设计

### 11.1 构建与打包

| 项 | 方案 |
|----|------|
| 构建 | electron-vite（主/preload/渲染三目标）+ Vite library mode 构建共享包 |
| 打包 | electron-builder：Windows NSIS / macOS dmg（arm64+x64）/ Linux AppImage+deb |
| 信创 | P3 交付 linux-arm64（麒麟/UOS）；loongarch 需社区 prebuilt 原生模块支持，列观察项 |
| 原生模块 | node-pty、better-sqlite3 用 prebuildify 预编译，避免用户机编译 |
| 体积目标 | ≤ 180MB（Electron 底座 ~120MB + xterm/monaco-lite/运行时） |

### 11.2 自动更新

electron-updater：更新元数据（latest.yml）托管于平台 MinIO 静态桶（私有化同源部署），签名校验；通道：stable / beta。

### 11.3 CI/CD（扩展现有三套 CI 收敛为 Jenkins 主线）

Jenkinsfile 新增 `desktop` stage：pnpm build → 单测 → electron-builder 三平台产物 → 上传 MinIO → 更新 latest.yml。与平台既有流水线同仓库触发（路径过滤 `gewu-desktop/**` 与 `packages/**`）。

### 11.4 分发拓扑

```
开发者 ──下载/更新──► MinIO 静态桶(安装包 + latest.yml)
        │登录──► gewu-gateway :8080 ──► 平台集群(K8s)
        └LLM直连──► LLM 厂商(可选 LOCAL-DIRECT 模式)
```

私有化部署：集群与更新桶同域内网交付，桌面端支持「内网更新源」配置。

---

## 十二、非功能需求

| 类别 | 指标 | 目标值 |
|------|------|--------|
| 性能 | 冷启动 → 工作区可用 | ≤ 3s |
| | 对话首 token（LOCAL-PROXY，内网网关） | ≤ 1.5s P95（不含模型推理） |
| | 工具调用（fs.read 1MB 内） | ≤ 100ms |
| 资源 | 空闲内存（三进程合计） | ≤ 500MB |
| | 安装包 | ≤ 180MB |
| 可用性 | 运行时崩溃恢复 | 自动重启 ≤ 3 次/会话，会话状态不丢（SQLite） |
| | 断网 | LOCAL 模式全功能；REMOTE 模式优雅降级并提示 |
| 可靠性 | 同步幂等 | 消息级 client_id 去重，重放零副作用 |
| 安全 | 密钥落盘 | 零明文（safeStorage） |
| 兼容 | OS | Windows 10+ / macOS 12+ / Ubuntu 20.04+（glibc≥2.31）；arm64（P3） |

---

## 十三、风险评估

| # | 风险 | 等级 | 影响 | 缓解措施 |
|---|------|------|------|----------|
| R1 | **双引擎行为漂移**（TS lite 引擎与 Java 引擎输出/事件序列不一致） | 🟠 中 | 本地/远程体验割裂、工具调用行为不可预期 | 事件协议契约测试 + 共享题集 fixture 双端回放断言；本地引擎显式标注能力边界（不支持编排/认知） |
| R2 | **平台侧依赖滞后**：D-3 幂等未修复（阻塞 P2 同步）、SSE 广播未 Redis 化（阻塞 P3 协作/HITL） | 🟠 中 | 桌面端协同里程碑顺延 | 桌面排期与平台 P0 修复联动（本设计已将 D-3 列为平台输入 N3）；P2 前置检查点 |
| R3 | **团队带宽**（平台自身 P0 债务 + 桌面端新模块并行） | 🟠 中 | 双线进度风险 | 严格分期：Phase 1 只做「壳+远程模式+最小本地引擎」；共享包抽离独立成 Phase 0 小步交付 |
| R4 | Electron CVE / Chromium 漏洞 | 🟡 低-中 | 安全合规风险 | 版本跟进策略（每季度评估升级）+ Dependabot；渲染层 sandbox 全开缩小攻击面 |
| R5 | 原生模块跨平台编译（node-pty/better-sqlite3，信创 arm64） | 🟡 低 | 信创交付延期 | prebuildify 预编译矩阵；兜底方案：终端降级为无 PTY 命令面板、SQLite 降级 sql.js |
| R6 | LLM 代理增加平台负载/被滥用 | 🟡 低 | 服务端成本与稳定性 | 网关既有限流复用 + 代理端点独立预算上限 + clientRequestId 幂等 |
| R7 | ChatPage 大组件抽离工作量超预期（550 行内联渲染） | 🟡 低 | Phase 0/1 延期 1-2 周 | 抽离与桌面新页并行；先以「包内直接引用 + 拆分」过渡，不做完美抽象 |

**置信度说明**：风险识别基于已核实的源码事实（D-3、SSE 内存广播、ChatPage 规模均为实读证据），等级评定含主观成分（置信度：中高）。

---

## 十四、实施路线图

> 排期为相对周期（人月估算按 1 名全时开发），预留 10% 调整空间；与平台分析报告 §9/§10 路线图对齐。

### Phase 0：共享包抽离（1 周）

- pnpm workspace 初始化；`packages/agent-ui` 抽离 protocol / api-client / process / chat-ui（timeline）/ ui / theme；
- web 端以最小改动切到共享包（仅 chat 相关路径先行）；
- 交付物：共享包 + web 回归通过 + IPC 契约草案（zod schema）。

### Phase 1：可用版桌面 Agent（3-4 周）

- Electron 壳（主进程/ preload/ Vite 渲染）、工作区选择器、连接向导（登录网关）；
- RemoteRuntimeAdapter（HTTP SSE，复用 api-client）→ 聊天/会话/Agent 市场/技能库页跑通；
- 本地运行时 MVP：ReAct 执行器 + fs/shell/git 工具 + 三级权限模式 + PermissionDialog + SQLite；
- 交付物：内测版安装包（三平台），G1 达标。

### Phase 2：模型共享与同步（3-4 周）

- 平台侧：LLM 代理端点（N1）+ D-3 幂等修复（N3）+ DesktopSync 端点（N2）；
- 桌面侧：ProviderRegistry 双源、会话上行/下行同步、执行记录上报、资源清单缓存、本地 MCP stdio；
- 工作区上下文加载器（AGENTS.md / rules / skills，兼容本仓约定）；
- 交付物：LOCAL-PROXY 默认模式可用，G3 部分达标。

### Phase 3：协作与分发（3-4 周）

- 平台侧：SSE 广播 Redis Pub/Sub 化（N4，与平台多副本改造合并实施）；
- 桌面侧：协作会话参与、HITL 远程审批中心、云端沙箱回退、编排/工作流远程视图；
- electron-builder 三平台签名 + electron-updater + Jenkins desktop stage + 信创 arm64 包；
- 交付物：正式版发布（G3/G5 达标）。

### Phase 4：远期演进

- 审计链同步（N8）、多端会话移交（深链唤起）、设备管理后台、Monaco 只读代码浏览、本地多任务子代理、平台策略包下发（企业权限管控）。

### 里程碑依赖关系

```
Phase 0 ──► Phase 1 ──► Phase 2 ──► Phase 3 ──► Phase 4
                        │            │
                        │            └─ 依赖平台 N4(SSE Redis化)
                        └─ 依赖平台 N3(D-3幂等) + N1/N2 新端点
```

---

## 十五、附录

### 附录 A：gewu-desktop 目标目录结构

```
gewu-desktop/
├── package.json                  # pnpm workspace 成员
├── electron-vite.config.ts
├── electron/                     # 主进程与 preload
│   ├── main/
│   │   ├── index.ts              # 入口：窗口/托盘/单实例/生命周期
│   │   ├── ipc/                  # §5.2 通道 handlers（chat/fs/terminal/permission/sync/auth/workspace/mcp/app）
│   │   ├── services/             # AuthService / ConfigService / UpdaterService / DeepLinkService
│   │   ├── permission/           # PermissionService 策略仲裁 + 白名单
│   │   └── terminal/             # TerminalManager（node-pty 会话池）
│   ├── preload/index.ts          # contextBridge 类型化 API
│   └── runtime/                  # 本地 Agent 运行时（utilityProcess）
│       ├── index.ts              # 进程入口 + MessagePort 处理
│       ├── core/                 # react.executor / context.builder / event.bus / budget.guard
│       ├── llm/                  # openai.client / provider.registry（本地Key + 平台代理）
│       ├── tools/                # fs / shell / git / search / mcp.bridge / remote.proxy
│       ├── security/             # path.guard / command.validator / injection.detector / sanitizer
│       ├── mcp/                  # stdio 客户端池（@modelcontextprotocol/sdk）
│       └── store/                # SQLite（local_session/message/audit/usage/kv）
├── src/                          # 渲染进程（React + Vite）
│   ├── app/                      # App Shell / 工作区管理 / 命令面板 / desktopSlice
│   ├── adapters/                 # RuntimeAdapter 接口 + local/remote 两个实现
│   ├── features/                 # chat / terminal / editor(diff) / approval / settings / market / usage
│   └── components/               # PermissionDialog / FileTree 等桌面专属组件
└── build/                        # electron-builder 配置 / 图标 / 签名 / 更新源脚本
```

（仓库根新增 `packages/agent-ui` 共享包，见 §6.1。）

### 附录 B：与既有文档/债务的关联索引

| 关联项 | 位置 | 关系 |
|--------|------|------|
| 桌面端立项决策 | 分析报告 §9.3-6 | 本文档即该决策的落地设计 |
| client_id 幂等债务 D-3 | 分析报告 §6.6/§8 | Phase 2 前置，桌面为第一消费方 |
| SSE 多副本广播 | 分析报告 §9.3-1 | Phase 3 前置（Redis Pub/Sub 化） |
| LLM 协议缺陷 D-2/D-11 | 分析报告 §8 | TS 引擎实现时规避，不复制缺陷 |
| 双轨 API 封装 D-19 | 分析报告 §8 | 共享包 api-client 收敛 web 双轨 |
| 表现层三端规划 | docs/design/01/21/25 | 本文档承接「桌面客户端 Electron 28+」 |
| Vite 时代组件 | git commit `6def6aa` | PermissionDialog/TerminalPanel/FileTreeNode 参考蓝本 |

### 附录 C：能力分级总览（本地 vs 平台运行时）

| 能力 | 本地运行时 | 平台运行时 |
|------|:----------:|:----------:|
| 流式对话/思考/工具时间线 | ✅ | ✅ |
| 本地文件/终端/项目上下文 | ✅（独有） | ❌ |
| 工具安全链（注入/路径/黑名单/授权） | ✅（子集） | ✅（六层全量） |
| MCP | 本地 stdio | 服务端 sse/streamable |
| 沙箱 | 本地 Docker（可选） | 云端 Docker 沙箱 |
| 复杂度路由/预算四维/语义缓存 | 简化 BudgetGuard | ✅ |
| Wenshi 认知推理/记忆向量 | ❌ | ✅ |
| 编排（PIPELINE/SUPERVISOR/SWARM/DEBATE） | ❌ | ✅ |
| HITL 审批 | 本地授权弹窗 | 平台审批流 + 桌面订阅 |
| 审计/用量 | 本地记录 + 上报 | 服务端记账/哈希链 |

---

*文档完 | 下一步：① 平台侧排期确认（N1~N4 增量）② Phase 0 共享包抽离启动 ③ UI 视觉规范另行立项*
