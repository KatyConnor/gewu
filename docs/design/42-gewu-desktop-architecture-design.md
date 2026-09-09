# gewu-desktop 桌面端模块架构设计

> **文档编号**：42 | **版本**：V2.2 | **日期**：2026-08-20
> **设计角色**：资深技术架构师（角色03）
> **状态**：评审中
> **前置阅读**：`docs/design/01-technical-architecture.md`、`docs/design/21-unified-architecture.md`、`docs/design/25-unified-deployment.md`、`docs/architecture/PROJECT-FULL-ANALYSIS-REPORT-2026-08.md`
> **数据来源**：仓库源码实读（commit `7fc231b` 工作区）+ codebase-memory 知识图谱结论交叉验证；置信度标注随文附注
>
> **修订记录**：
>
> | 版本 | 日期 | 修订内容 |
> |------|------|----------|
> | V1.0 | 2026-08-20 | 初稿：本地 TS 轻量运行时 + 平台远程运行时的"能力分级"架构 |
> | V2.0 | 2026-08-20 | **需求澄清后重大修订**：确立"单机版保障与平台同级的完整智能体能力"为设计前提，废止 V1 的 lite 定位；ADR-002/005 被取代（本地运行时改为嵌入 Java 引擎 + 桌面 SPI 适配集），ADR-006 修订（本地存储 H2）。"一引擎两形态"替代"双运行时" |
> | V2.1 | 2026-08-20 | ADR-001 复决：应用户要求对桌面壳技术做市场调研（Electron 44 / Tauri 2.11 / Wails 2.14 / Neutralino / JavaFX+JCEF / 原生自绘六候选评估矩阵），结论维持 Electron；新增复决触发条件（写死退出机制） |
> | V2.2 | 2026-08-20 | §12 细化：新增 12.5「平台迭代→桌面影响的变更传播矩阵」与协同版本协商握手；12.2 扩展为三层独立升级机制（应用整包 / 引擎 jar 热替换 / 资源数据驱动）；12.4 细化分发渠道与版本策略 |

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

1. **单机形态**：未使用格物平台的企业或个人，本地安装后即可获得**与平台同级的完整智能体能力**（对话、Agent 定义与管理、技能、本地工具执行、沙箱、多 Agent 编排、预算控制、记忆、审计），零平台依赖；
2. **协同形态**：已使用平台的企业团队，桌面端接入集群后**叠加**协同能力（共享模型配置、Agent/技能市场资源、多端会话同步、团队协作、远程审批、云端沙箱回退、集中审计与用量治理）。

两种形态共用同一个智能体引擎与同一套产品能力，差异仅在 SPI 适配层的接线（本地资源 vs 平台资源）。**单机不是能力阉割版，协同不是能力来源**——这是 V2.0 修订确立的设计前提。

### 1.2 范围

- **包含**：桌面端进程架构、桌面运行时（引擎嵌入方案）、桌面 SPI 适配集、工具与安全体系、UI 复用策略、平台接入与协同协议、能力边界、分发与更新、安全设计、实施路线
- **不包含**：平台后端既有功能的重新设计（见 docs/design/01~41）；视觉规范（另行 UI 设计文档）
- **涉及平台侧增量**：第十章列出平台侧需新增/改造的接口与引擎前置修复，作为平台侧排期输入

### 1.3 术语与缩略语

| 术语 | 定义 |
|------|------|
| 桌面端 / gewu-desktop | 本模块，Electron 桌面应用 |
| 桌面运行时（desktop-runtime） | 新建的 Java 模块：Spring Boot 宿主 + gewu-agent-engine + 桌面 SPI 适配集，以 JVM 子进程形态嵌入桌面端 |
| 一引擎两形态 | 同一个 gewu-agent-engine 分别服务于平台服务端与桌面单机，差异仅在 SPI 适配 |
| 工作区（Workspace） | 用户打开的本地项目目录，会话与工具权限的边界单元 |
| 桌面 SPI 适配集 | desktop-runtime 内对引擎 16+ SPI 的本地化实现集合（本地持久化/模型配置/权限/审计/沙箱等） |
| HITL | Human-in-the-Loop，人机协同审批 |
| MCP | Model Context Protocol，工具/资源接入协议 |
| SSE | Server-Sent Events，平台流式推送协议 |
| 引擎 API Profile | desktop-runtime 对外暴露的、与平台 REST/SSE 契约同构的最小端点集 |

### 1.4 参考资料

| 资料 | 用途 |
|------|------|
| ZCode（本仓 zcode-rules.md / AGENTS.md） | 本地 Agent 工具的行为对标（指令优先级、权限模式、技能/规则加载） |
| gewu-agent-engine 源码（144 文件 / 10,101 行 / 21 SPI + 8 NoOp 默认实现） | **桌面运行时的直接复用对象**（非移植蓝本） |
| gewu-web 源码（chat.ts / agentProcess.ts / AIProcessTimeline.tsx 等） | UI 与协议解析复用来源 |
| git 历史 commit `6def6aa` | Vite 时代 PermissionDialog / TerminalPanel / FileTreeNode / CodeEditor 组件可恢复 |
| 分析报告 §9.3-6 | 桌面端立项决策来源 |

---

## 二、背景与目标

### 2.1 业务背景

格物平台已建成覆盖 SDLC 全生命周期的 Web 端协作平台（17 个业务域 / 33 个 Controller / 26 个前端页面），其核心资产 `gewu-agent-engine` 是**刻意零 DB/Web 依赖、SPI + NoOp 默认实现、可独立启动**的认知 Agent 引擎框架（`NoOpPersistenceService` 保证零业务实现可运行；自动装配全部 `@ConditionalOnMissingBean`）。

这一架构决定在 V2.0 中获得第二重回报：**引擎本身就可以是桌面单机版的智能体内核**。桌面端无需"再造一个引擎"，而是把同一个引擎以 JVM 子进程形态嵌入 Electron，配上本地化的 SPI 适配集。由此，单机用户获得与平台完全相同的智能体能力（同一份代码、同一套行为），平台用户则在桌面端上叠加协同。

V1.0 曾设计"TS 移植引擎核心子集"的轻量方案，经需求澄清（单机版必须保障完整智能体能力）后废止——TS 子集移植在工程上必然造成与 Java 引擎的能力漂移与永久性落后，与本前提矛盾。

### 2.2 对标分析（ZCode 能力矩阵 → gewu-desktop 方案）

| ZCode 能力 | gewu-desktop 实现（单机形态即具备） | 平台协同形态叠加 |
|------------|--------------------------------------|------------------|
| 本地对话 + 流式思考/工具时间线 | 引擎 ReAct 流式执行 + 复用 web 时间线组件（ProcessTracker/AIProcessTimeline） | 云端会话同源 |
| 文件读/写/编辑（diff 确认） | 本地工具（Java 实现，PathGuard + diff 授权） | 审计上送哈希链 |
| 终端命令执行 | 本地 shell 工具（命令黑名单 + 授权）+ node-pty 交互终端 | 云端沙箱回退 |
| 项目上下文（AGENTS.md/rules/skills） | 工作区上下文加载器，兼容本仓既有约定 | 平台技能在线安装 |
| 权限模式（只读/写确认/全自动） | PermissionService 本地实现 = 三级权限模式 + 授权弹窗 | 企业策略包下发（P4） |
| MCP 接入 | 引擎自带 StdioMcpClient（本地 stdio）+ 桌面 MCP 管理 | 平台 MCP 配置同步 |
| 技能系统 | 本地技能库（等价平台 skills 域） | 技能市场安装/发布 |
| 会话管理 | 本地 H2 持久化（含历史/搜索/分叉预留） | 多端同步、协作会话 |
| 模型接入 | 本地多厂商模型配置（Key 由 Electron 安全保管，注入引擎内存） | 平台 LLM 代理（密钥不出服务端） |
| 多 Agent 协作/子代理 | **引擎编排能力本地可用**（PIPELINE/SUPERVISOR/SWARM/DEBATE + 自主目标）——TS 方案无法企及，引擎嵌入方案免费获得 | 平台侧执行记录归集 |

### 2.3 建设目标

| # | 目标 | 衡量指标 | 目标值 |
|---|------|----------|--------|
| G1 | **单机完整智能体能力** | 引擎能力域（对话/工具/沙箱/编排/预算/记忆/审计）在单机形态可用率 | 100%（平台固有能力除外，见第九章清单） |
| G2 | 合理包含 Web 端已实现 AI 能力 | 复用页面/组件覆盖 | ≥ 70% 组件级复用 |
| G3 | 接入集群协同 | 登录网关后叠加：共享模型、资源同步、会话协同、HITL、云端沙箱、用量治理 | Phase 3 交付 |
| G4 | 平台侧最小增量 | 平台新增代码 ≤ 1 个 Controller + 1 个代理端点 + 引擎 2 项前置修复 | 不新建服务 |
| G5 | 可分发可运维 | 三平台安装包 + 签名自动更新 + 引擎进程守护 | Phase 3 交付 |
| G6 | 引零漂移 | 桌面与平台智能体行为一致性 | 同一引擎代码，契约测试守护（非移植） |

### 2.4 约束条件

| 类型 | 约束 |
|------|------|
| 团队 | 单人/小团队，Java + TS 技术栈（Java 引擎嵌入方案与团队技能强匹配） |
| 既有决策 | docs/design/01/21 已确定 Electron 28+ 路线 |
| 协议 | 桌面运行时对外契约必须与平台 REST/SSE 同构（复用 ChatStreamEvent / Result 信封），不另起协议 |
| 引擎前置 | 引擎两项 P0 债务必须先修：D-2（Message 缺 toolCalls 序列化，否则本地多轮工具调用对严格厂商 400）、D-10（LLM 超时硬编码 120s）——两项均为桌面 Phase 1 阻塞项 |
| 安全 | 等保 2.0 三级延伸：本地工具执行可审计；密钥不得明文落盘；localhost 服务需防本机其他进程未授权访问 |
| 体积 | 引擎嵌入带来 JVM 体积（jlink 定制运行时 ~50MB），安装包目标放宽至 ≤ 180MB（V1 为纯 TS 方案的 180MB 内不受影响，但预算构成改变） |

---

## 三、总体架构设计

### 3.1 架构风格

**Electron 三进程桌面应用 + JVM 引擎子进程（一引擎两形态）+ 平台同构 API Profile + 共享 UI 包**。

核心思路（置信度：高，基于引擎架构事实）：

1. **同一个引擎，两种部署形态**：`gewu-agent-engine` 在平台侧嵌入 `gewu-interface` 主服务（现状），在桌面侧嵌入新建的 `gewu-desktop-runtime` 模块（Spring Boot 宿主 + 桌面 SPI 适配集）。智能体能力（ReAct 循环、工具体系、安全链、预算、编排、MCP、HITL、记忆 SPI）全部来自同一份 Java 代码，**结构性消除行为漂移**。
2. **桌面 SPI 适配集**是唯一的本地化胶水层：把引擎的 16+ SPI 接到本地资源（H2 持久化、本地模型配置、权限弹窗、本地 Docker 沙箱、本地审计、stdio MCP 配置源）。
3. **对外契约与平台同构**：desktop-runtime 暴露与平台同形状的最小端点集（引擎 API Profile，§10.1），渲染层因此可以用**同一个 api-client、仅切换 baseURL** 访问本地引擎或平台网关——共享包复用度最大化。
4. **渲染层整体复用 gewu-web**：web 端页面均为纯客户端组件，经共享包 `@gewu/agent-ui` 双端消费（ADR-003 不变）。
5. **协同形态 = 资源接线切换**：登录平台后，桌面运行时的部分 SPI 适配从"本地源"切换/叠加为"平台源"（模型走 LLM 代理、资源清单从平台拉取、会话双向同步、审批订阅）——引擎与 UI 不变。

### 3.2 系统上下文图（C4 Level 1）

```
                       开发者本地机器
┌───────────────────────────────────────────────────────────────────┐
│                     gewu-desktop (Electron)                        │
│                                                                   │
│  ┌──────────────┐   ┌─────────────────────┐   ┌────────────────┐ │
│  │ 渲染进程      │   │ 主进程               │   │ JVM 引擎子进程  │ │
│  │ React UI     │──►│ 窗口/托盘/更新        │──►│ desktop-runtime│ │
│  │ (复用共享包)  │   │ safeStorage密钥托管  │   │ (jlink运行时)   │ │
│  └──────────────┘   │ node-pty终端/进程守护 │   │ gewu-agent-    │ │
│         │           └─────────────────────┘   │ engine(全量)    │ │
│         │ HTTP(SSE) 直连 127.0.0.1            │ + 桌面SPI适配集  │ │
│         └────────────────────────────────────►│                 │ │
│                                               └───────┬────────┘ │
│  ┌────────────────────────────────────────────────────┴────────┐ │
│  │ 本地资源: 工作区文件系统 │ shell(ProcessBuilder) │ 本地Docker  │ │
│  │   本地 stdio MCP │ H2 会话库 │ 本地审计日志 │ 本地模型 Key     │ │
│  └─────────────────────────────────────────────────────────────┘ │
└───────────────┬─────────────────────────────┬─────────────────────┘
                │ HTTPS (JWT Bearer)          │ 用户自有 API Key 直连
                ▼ 协同形态                     ▼ (可选)
┌───────────────────────────────┐   ┌──────────────────┐
│   格物平台集群 (K8s, 2~10副本)  │   │  LLM 厂商 API     │
│  gateway :8080 │ interface    │   │  DeepSeek/智谱/   │
│  :8081 │ sandbox :8082        │   │  豆包/千问...     │
│  MySQL │ PG │ DragonflyDB │    │   └──────────────────┘
│  MinIO │ SearXNG              │
└───────────────────────────────┘
```

**外部系统交互说明**：

| 外部系统 | 交互方式 | 协议 | 用途 |
|----------|----------|------|------|
| 格物平台集群（协同形态） | 同步（REST/SSE） | HTTPS + JWT Bearer | 登录、共享模型代理、资源同步、会话协同、远程审批、云端沙箱、用量/审计上送 |
| LLM 厂商 API | 同步（SSE 流式） | HTTPS + API Key | 本地模型配置（引擎 OpenAiCompatibleClient 直连） |
| 本地文件系统/终端 | 本地调用 | Java NIO / ProcessBuilder | 本地工具执行 |
| 本地 Docker | 本地调用 | docker CLI（ProcessBuilder 封装） | 本地沙箱执行器（检测可用，缺省降级） |
| 本地 MCP 服务器 | 子进程 stdio | JSON-RPC | 引擎 StdioMcpClient 桥接 |

### 3.3 桌面端内部架构（C4 Level 2）

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          gewu-desktop 进程拓扑                           │
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ 渲染进程 (Chromium, sandbox+contextIsolation)                       │  │
│  │                                                                   │  │
│  │  ┌─────────────┐ ┌──────────────┐ ┌────────────────────────────┐ │  │
│  │  │ App Shell   │ │ 页面模块       │ │ 连接层 (同构 API 客户端)    │ │  │
│  │  │ 工作区/命令面板│ │ chat/terminal│ │ ┌────────────────────────┐│ │  │
│  │  │ 侧边栏/Tab   │ │ editor/      │ │ │ ConnectionManager      ││ │  │
│  │  └─────────────┘ │ agents(本地)  │ │ │  ├ 单机: 127.0.0.1:port ││ │  │
│  │                  │ skills/mcp   │ │ │  └ 协同: gateway URL    ││ │  │
│  │  ┌─────────────┐ │ approval/    │ │ └────────────────────────┘│ │  │
│  │  │ @gewu/agent-ui 共享包          │ │  同一 api-client / SSE 解析│ │  │
│  │  │ ChatPage/AIProcessTimeline/   │ │  仅 baseURL 与凭据不同     │ │  │
│  │  │ ProcessTracker/api-client/    │ └────────────────────────────┘ │  │
│  │  │ protocol(事件类型)/theme       │                                │  │
│  │  └─────────────┘                                                │  │
│  └──────────────────────────┬────────────────────────────────────────┘  │
│                             │ preload (contextBridge 类型化API)          │
│  ┌──────────────────────────▼────────────────────────────────────────┐  │
│  │ 主进程 (Node.js)                                                   │  │
│  │  窗口/托盘/菜单 │ 单实例锁 │ deep-link │ electron-updater           │  │
│  │  SecretVault(safeStorage: 平台token + 本地模型Key)                  │  │
│  │  EngineSupervisor(JVM子进程守护: 启动/心跳/崩溃重启≤3次)             │  │
│  │  TerminalManager(node-pty) │ IPC注册中心                           │  │
│  └──────────────────────────┬────────────────────────────────────────┘  │
│                             │ spawn + stdin 注入临时令牌/密钥             │
│  ┌──────────────────────────▼────────────────────────────────────────┐  │
│  │ gewu-desktop-runtime (JVM 子进程, 127.0.0.1 随机端口)               │  │
│  │                                                                   │  │
│  │  ┌─────────────────────────────────────────────────────────────┐ │  │
│  │  │ 引擎 API Profile（与平台契约同构的最小端点集，§10.1）           │ │  │
│  │  └─────────────────────────────────────────────────────────────┘ │  │
│  │  ┌─────────────────────────────────────────────────────────────┐ │  │
│  │  │            gewu-agent-engine（与平台同一份代码，全量）         │ │  │
│  │  │  ReactAgentExecutor │ ToolExecutor+安全链 │ BudgetController │ │  │
│  │  │  OrchestrationEngine(4模式) │ StdioMcpClient │ HITL │ 记忆SPI │ │  │
│  │  └─────────────────────────────────────────────────────────────┘ │  │
│  │  ┌─────────────────────────────────────────────────────────────┐ │  │
│  │  │ 桌面 SPI 适配集（本地化胶水层）                                │ │  │
│  │  │ DesktopPersistenceService(H2) │ DesktopSessionContext        │ │  │
│  │  │ DesktopModelProvider(本地Key,SM4) │ DesktopPermissionService  │ │  │
│  │  │ DesktopHitlGateway(授权弹窗桥) │ DesktopAuditService          │ │  │
│  │  │ LocalDockerSandboxExecutor │ DesktopMcpConfigSource           │ │  │
│  │  │ WorkspaceTools(@ToolProvider: fs/shell/git/search)            │ │  │
│  │  └─────────────────────────────────────────────────────────────┘ │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.4 连接模式（替代 V1"运行模式路由矩阵"）

| 模式 | 引擎位置 | 模型来源 | 资源（Agent/技能/MCP） | 差异 |
|------|----------|----------|------------------------|------|
| **单机（默认，可离线）** | 本地 JVM 子进程 | 本地配置的厂商 Key（safeStorage 托管） | 本地库（H2 + 工作区目录） | 完整智能体能力 |
| **协同（登录平台）** | 本地 JVM 子进程（不变） | 平台 LLM 代理（默认）/ 本地 Key（可覆盖） | 本地库 + 平台资源清单（拉取合并） | 叠加协同能力；本地能力不减少 |

**关键语义**：协同模式下引擎仍在本地执行（文件工具必须触本地资源），改变的只是"资源与协同数据的来源"。只有用户显式选择"云端执行某 Agent"（该 Agent 依赖云端沙箱/平台工具）时，才将该次执行路由到平台引擎（经网关调用 `/ai/chat/stream`）——引擎 API Profile 同构使这种路由对渲染层透明。

---

## 四、关键架构决策（ADR）

### ADR-001：桌面壳技术选型 —— Electron（V2.1 经市场调研复决，维持）

| 属性 | 内容 |
|------|------|
| 状态 | 已批准（V2.1：应用户要求对壳层技术做市场调研复决，结论维持 Electron） |
| 决策 | Electron 44+（随当前稳定版），electron-vite 构建；本地系统能力分工：交互终端（node-pty）/进程守护/密钥托管在主进程，Agent 能力在 JVM 子进程 |

**市场调研（2026-09，版本经 GitHub Releases 核实）**：

| 候选 | 现状（核实时点） | 壳层体积/内存 | 后端语言 | 本项目关键障碍 |
|------|------------------|---------------|----------|----------------|
| Electron 44.3（2026-09，持续高频迭代） | 成熟稳定，同类事实标准 | 底座 ~85-100MB / 空闲 ~200-300MB | TS（Node） | 无 |
| Tauri 2.11.5（2026-07，2.x 已两年成熟期） | 稳定，插件生态成型 | 壳 ~5-15MB（依赖系统 WebView）/ 内存显著更低 | Rust | ① Linux 渲染依赖系统 webkit2gtk 4.1（官方支持清单不含麒麟/UOS/loongarch，信创系统 WebView 版本普遍老旧，渲染/IME/GPU 兼容不可控）；② 后端逻辑（PTY/密钥/守护/更新器）需 Rust——团队零储备；③ 本项目 JVM 子进程已是体积内存大头，Tauri 的节省（安装包 ~75MB / 内存 ~100-200MB）不改变可行性结论，属边际收益 |
| Wails 2.14.0（2026-08） | v2 稳定；v3 自 2025 起 alpha 至今未 GA（最新稳定发布仍为 v2 线） | 壳 ~10-20MB（系统 WebView） | Go | 同 Tauri 的 WebView 信创风险；v3 未转正不宜押注；Go 为团队第二新语言 |
| Neutralino | 轻量 | ~2-5MB | TS/扩展 | 无成熟 PTY/更新器/签名链路，社区规模小，不适合复杂桌面产品 |
| JavaFX+JCEF / Compose Desktop（统一进 JVM） | 可行 | — | Java | 需用 Java 重写全部 UI（16.6k 行 React），直接违背 G2 复用目标 |
| 原生自绘（Zed/GPUI 路线） | 极致性能标杆 | 最优 | Rust | 自研 GPU UI 框架，工程量级不适配小团队，且需重写 UI |

**决策理由（按权重排序）**：
1. **信创渲染一致性（决定性）**：目标平台含麒麟/UOS/arm64/loongarch。Electron 自带 Chromium = 所有平台同一渲染引擎；Tauri/Wails 依赖系统 WebView，在信创发行版上版本老旧且不可控——这是同类跨平台框架在该目标下的硬伤；
2. **行业同类证据**：ZCode 同类产品 Cursor / Windsurf / VS Code / Claude Desktop / Cherry Studio 均为 Electron；Zed 证明原生路线可行但代价为自研 UI 框架；
3. **生态匹配度**：本项目刚需链路——node-pty（终端）、safeStorage（密钥）、electron-updater（签名更新）、electron-builder（NSIS/dmg/AppImage/deb/arm64）——在 Electron 均为一等公民且有大量生产案例；Tauri 对应能力需 Rust 插件组合拼装，Linux AppImage 更新存在已知限制；
4. **团队技能与工期**：纯 TS 壳层 + Java 运行时 = 两门既有语言；引入 Rust/Go 为第三门语言，小团队带宽下成本显著；
5. **收益边际化**：V2 架构将引擎移入 JVM 后，Electron 相对 Tauri 的体积/内存差距在本项目总盘子里占比有限（详见 §13 预算），非决定性维度。

**诚实记录的代价与缓解**：安装包较 Tauri 方案多 ~75-85MB、空闲内存多 ~100-200MB（预算已容纳，§13）；Chromium CVE 跟进义务（季度版本评估 + Dependabot，§11.1）。

**复决触发条件（写死退出机制）**：Phase 0 Spike 若出现以下任一情形，重新召开本 ADR——① loongarch/arm64 信创目标上 Electron（含 Chromium）构建不可行或不可分发；② 安装包实测 >250MB 且无法通过裁剪收敛；③ 出现"系统 WebView 在信创发行版上成熟可用"的新事实（如 Tauri 官方支持信创或信创系统预装 WebView2 等价物）。

### ADR-002（V1，已取代）：~~TS 移植引擎核心子集~~ → **ADR-002B：嵌入 Java 引擎（一引擎两形态）**

**V1 决策回顾（已废止）**：在 utilityProcess 用 TS 实现 lite 版 ReAct 执行器，能力分级"本地 lite / 远程 full"。
**废止原因**：需求澄清确立"单机版须保障与平台同级的完整智能体能力"。TS 子集移植无法承载编排/预算/安全链/记忆等全域能力，且与 Java 引擎必然行为漂移、永久性落后（小团队维护两套引擎不可行）。

| 属性 | ADR-002B（现行决策） |
|------|------|
| 状态 | 已批准（取代 ADR-002） |
| 背景 | 三方案对比：a) 纯远程瘦客户端（无本地能力，违背单机前提）；b) TS 全量重写引擎（≈10k 行重复建设 + 永久漂移）；c) **嵌入 gewu-agent-engine** |
| 决策 | 方案 c：新建 `gewu-desktop-runtime` Maven 模块（Spring Boot 宿主），依赖 gewu-agent-engine jar，实现桌面 SPI 适配集，以 jlink 定制 JRE 子进程运行；桌面构建管线打包该模块产物 |
| 理由 | ① **能力平价由结构保证**：同一份引擎代码，编排/预算/安全链/MCP/HITL 免费获得，G6"零漂移"不依赖测试覆盖而是依赖代码同一性；② 引擎本就是按"可独立复用框架"设计的（21 SPI + 8 NoOp 默认实现 + AutoConfiguration.imports，`NoOpPersistenceService` 保证零业务可启动）——嵌入是其设计意图的自然延伸；③ 团队 Java 能力现成，引擎后续演进（wenshi 收敛、认知实验结论）自动惠及桌面；④ 工具/沙箱/MCP 在 Node 与 JVM 中均为一等能力，无生态短板 |
| 后果 | + 单机完整能力、双端行为一致、引擎单一代码源；− 安装包 +50~70MB（jlink JRE）、+30~40MB（jar 依赖），内存 +~200MB（-Xmx 上限管控）；− 需要完成引擎前置修复 D-2/D-10（本就是平台 P0 债务，顺势推进）；− desktop-runtime 的 Spring Boot 宿主与 SPI 适配集为新增 Java 代码（估算 2.5~3.5k 行，可控） |

### ADR-003：UI 复用策略 —— 抽离共享包 `@gewu/agent-ui`（保持不变）

| 属性 | 内容 |
|------|------|
| 状态 | 已批准（同 V1） |
| 决策 | pnpm workspace + `packages/agent-ui`（protocol / api-client / process / chat-ui / ui / theme）；gewu-web 渐进迁移引用，desktop 第一天直接消费 |
| V2 增益 | 桌面运行时与平台 API 同构后，共享包 api-client 的价值放大：**同一套页面组件可绑定不同 baseURL**（本地引擎/平台网关），"本地 Agent 管理"与"云端 Agent 管理"可以是同一组件的两个实例 |

### ADR-004：统一事件协议 —— 双端共用 ChatStreamEvent（保持不变，受益放大）

| 属性 | 内容 |
|------|------|
| 状态 | 已批准（同 V1） |
| V2 说明 | 协议同一性由**代码同一性**背书：desktop-runtime 直接复用平台协议 DTO（需将 ChatStreamEvent/ChatRequest 等协议类下沉到 gewu-common 或独立 gewu-protocol 模块，见 §10.3 P5），V1 中的"三端同步"风险收窄为"Java 内两模块依赖"，契约测试降级为回归保障而非漂移防线 |

### ADR-005（V1，已取代）：~~运行时置于 utilityProcess~~ → **ADR-005B：运行时置于 JVM 子进程（spawn + 守护）**

| 属性 | 内容 |
|------|------|
| 状态 | 已批准（取代 ADR-005） |
| 决策 | desktop-runtime 以独立 JVM 子进程运行（Electron 主进程 `child_process.spawn`）：绑定 127.0.0.1 随机端口；启动时经 stdin/环境变量注入**临时访问令牌**（主进程随机生成，防本机其他进程未授权访问）；EngineSupervisor 守护（心跳/退出码分类/崩溃自动重启 ≤3 次，连续失败进入人工诊断态） |
| 理由 | JVM 进程无法置于 Electron utilityProcess；独立进程天然崩溃隔离；HTTP/SSE 本地回环是渲染层消费流式的最自然通道（与远程模式同构） |
| 后果 | + 崩溃隔离、进程独立升级（引擎 jar 可独立热替换，为后续"引擎自更新"预留）；− 进程间安全需令牌机制（已设计） |

### ADR-006（修订）：本地存储 H2 文件库 + client_id 幂等同步

| 属性 | 内容 |
|------|------|
| 状态 | 已批准（V2 修订：SQLite → H2） |
| 决策 | ① 本地会话/消息/审计/用量持久化采用 **H2 文件模式（纯 Java，零原生库）**，表结构对齐平台 `session`/`session_message` 字段语义；② 同步上行以 client_id 幂等（依赖平台 D-3 修复）；③ 增量下行按 updated_at 水位 |
| 修订理由 | V1 选 better-sqlite3 基于"运行时在 Node"的旧前提；运行时移入 JVM 后，存储随之进 JVM。H2 相比 sqlite-jdbc 免去原生库分发（信创 arm64/loongarch 无 prebuilt 顾虑），纯 Java 跨平台一致 |
| 后果 | + 零原生依赖、与 JVM 同生命周期；− 单文件库性能弱于 SQLite（桌面单用户场景无影响）；− 与平台 MySQL 方言差异由同步层字段映射吸收 |

### ADR-007：平台 LLM 代理 —— 密钥不出服务端（保持不变）

| 属性 | 内容 |
|------|------|
| 状态 | 已批准（同 V1） |
| V2 语义 | 协同形态的默认模型源；单机形态不依赖此端点（本地 Key）。桌面运行时的 ProviderRegistry 以 LlmProvider SPI 双源实现：`DesktopProxyLlmProvider`（HTTP 指向平台代理）与 `DesktopLocalLlmProvider`（引擎 OpenAiCompatibleClient 直连厂商） |

### ADR-008（新增）：本地沙箱执行器 —— docker CLI 封装，缺省降级

| 属性 | 内容 |
|------|------|
| 状态 | 已批准 |
| 决策 | `LocalDockerSandboxExecutor` 实现 SandboxExecutor SPI：经 ProcessBuilder 调用本机 `docker run --rm`（资源限额 CPU/内存/磁盘/网络开关对齐平台沙箱参数语义），不引入 docker-java 依赖；本机无 Docker 时该执行器显式降级（`code_execute` 类工具禁用并在 UI 标注），工具直执行路径由 PermissionService 强制授权兜底；协同形态可回退云端沙箱 |
| 理由 | 引擎刻意零依赖，docker-java 不宜进引擎；CLI 封装零依赖且跨平台（Win/mac/Linux docker CLI 行为一致）；降级策略诚实外显优于静默弱化 |

---

## 五、进程模型与 IPC 设计

### 5.1 进程清单

| 进程 | 技术 | 职责 | 生命周期 |
|------|------|------|----------|
| 主进程 | Node.js (Electron Main) | 窗口/托盘/菜单、单实例锁、deep-link（gewu://）、自动更新、**SecretVault**（safeStorage：平台 token + 本地模型 Key）、**EngineSupervisor**（JVM 子进程守护）、TerminalManager（node-pty 交互终端）、IPC 注册中心 | 应用级 |
| 渲染进程 | Chromium (React + Vite) | 全部 UI；`sandbox: true`、`nodeIntegration: false`、`contextIsolation: true`；经 preload 类型化 API 访问能力 | 窗口级 |
| 引擎子进程 | JVM（jlink 定制运行时） | desktop-runtime：引擎 API Profile 端点 + gewu-agent-engine 全量 + 桌面 SPI 适配集 + H2 持久化 | 应用级（随主进程启停，崩溃自动重启） |
| MCP 子进程 | stdio JSON-RPC | 本地 MCP 服务器（引擎 StdioMcpClient 管理） | 按需 |

### 5.2 主进程 ↔ 引擎子进程

| 机制 | 说明 |
|------|------|
| 启动参数 | stdin 注入 JSON 引导配置：`{port, ephemeralToken, modelKeys(解密后), workspace}`；引擎不将密钥落盘，仅驻留内存 |
| 数据面 | 渲染进程经 preload 获得本地引擎 baseURL + 临时令牌后**直连** `http://127.0.0.1:{port}`（HTTP/SSE），主进程不做数据面代理（降低转发延迟与复杂度） |
| 控制面 | 心跳（引擎 `GET /engine/health` 每 10s）；EngineSupervisor 按退出码分类处理（OOM → 降堆重启 / 配置错 → 诊断态）；应用退出时优雅 SIGTERM + 超时强杀 |
| 端口安全 | 仅绑定 127.0.0.1；所有引擎端点（除 health）要求 `X-Engine-Token` = 临时令牌；每次应用启动令牌更换 |

### 5.3 IPC 通道契约（主进程 ↔ 渲染进程，preload 类型化）

| 通道 | 方向 | 载荷 | 说明 |
|------|------|------|------|
| `engine:status` | M→R | `{state: booting/ready/degraded/crashed, log?}` | 引擎生命周期状态推送 |
| `engine:restart` | R→M | — | 手动重启引擎（诊断态） |
| `secret:set/get-cleartext-never` | R→M | `{kind: platformToken/modelKey, cipher}` | 密钥仅经 safeStorage，渲染层永不接触明文 |
| `connection:mode` | R→M | `{mode: standalone/platform, gatewayUrl?, credentials?}` | 连接模式切换（登录流程由主进程代理） |
| `terminal:create/write/resize/exit` | R↔M | `{tabId, data, cols, rows}` | xterm.js ↔ node-pty（交互终端，独立于 Agent 工具执行） |
| `workspace:open/recent/list` | R→M | `{path}` | 工作区管理（含 AGENTS.md 检测） |
| `app:config:get/set` | R↔M | 配置键值 | 设置持久化 |
| `sync:trigger` | R→M | `{direction: push/pull}` | 协同模式同步触发（同步执行体在引擎子进程内） |
| `updater:check/install` | R→M | — | 自动更新 |

**契约保障**：通道常量与载荷 schema（zod）定义于 `@gewu/agent-ui/protocol/ipc`，preload 导出 API 以 TypeScript 接口约束。

> 与 V1 的差异：`chat:stream:*`、`tool:permission:*` 不再经主进程 IPC 转发——数据面直连本地引擎（SSE 事件/审批事件由引擎端点直接推送渲染层），主进程仅保留控制面与密钥面。

---

## 六、渲染层设计（UI 复用）

### 6.1 共享包 `packages/agent-ui` 结构（同 V1）

```
packages/agent-ui/
├── protocol/            # ChatStreamEvent 类型、事件常量、Result 信封、错误码、DTO、Desktop Engine API Profile 定义
├── api-client/          # 统一 HTTP 客户端（Bearer/X-Engine-Token 注入、Result 解包）+ SSE 解析器（自 chat.ts 抽离）
├── process/             # ProcessTracker、createProcessStreamHandler（纯 TS，零依赖）
├── chat-ui/             # ChatPage（拆分：SessionList/Composer/MessageBubble/ModelPicker/AgentPicker）+ AIProcessTimeline
├── ui/                  # MarkdownRenderer、FileCard、Toast、Select、Card 等
└── theme/               # themes.ts + CSS 变量
```

### 6.2 桌面端页面清单与复用映射（V2 修订）

| 桌面页面 | 来源 | 数据源绑定 | 说明 |
|----------|------|-----------|------|
| 工作区选择器（启动页） | 新建 | 本地 | 最近工作区、打开目录、AGENTS.md 检测 |
| 聊天工作台（核心页） | web ChatPage + ChatHomeView | 本地引擎 / 平台网关（连接层切换） | 会话列表带「本地/云端」标识；Composer 支持 @file 引用与截图粘贴；模型/Agent 选择器数据源随连接模式 |
| 过程时间线 | web AIProcessTimeline | 事件流 | 直接复用（协议同构保证） |
| Agent 管理（本地） | web AgentManagePage / MyAgentsPage 改造 | **本地引擎** `/agents` | 单机形态的 Agent CRUD/工具挂载/技能挂载 |
| Agent 市场 | web AgentMarketPage | **平台网关** `/agents/market` | 协同形态页；单机模式入口隐藏并引导 |
| 技能库 | web SkillLibraryPage / MySkillsPage | 本地 + 平台 | 本地技能 CRUD + 平台技能安装（写入工作区 .gewu/skills/） |
| MCP 管理 | web McpServerPage | 本地 + 平台 | 本地 stdio MCP（引擎 StdioMcpClient）/ 平台 MCP 配置同步两 Tab |
| 编排工作台 | web OrchestrationPage / WorkflowCanvas | **本地引擎** `/orchestration` | **单机可用**（引擎编排能力本地运行）；协同模式增加云端编排实例 |
| 终端面板 | Vite 时代 TerminalPanel 参考 + xterm.js | node-pty | 多 Tab；工具命令可"发送到终端"复现 |
| 文件树 / Diff 查看 | Vite 时代 FileTreeNode 参考 + react-diff-viewer | 本地 fs | 虚拟滚动、git 状态标记、写操作 diff 预览 |
| 权限/审批中心 | Vite 时代 PermissionDialog 参考 + web AuditCenterPage 逻辑 | 本地 HitlGateway 桥 + 平台 `/approvals` | **统一收口**：本地工具授权、本地编排 HITL、远程平台审批三类待办一屏管理 |
| 沙箱页 | web SandboxPage | 本地 Docker / 云端 | 本地沙箱状态（检测 Docker）+ 协同模式云端沙箱列表 |
| 设置 | web SettingsPage 骨架 | 本地 | 连接模式、模型源（本地 Key 管理）、权限模式、MCP、同步策略、更新通道 |
| 仪表盘/用量 | web UsagePage / DashboardPage | 本地 H2 聚合 + 平台 `/stats` | 本地 token/成本统计 + 协同模式平台用量 |
| 登录/连接向导 | web LoginPage 参考重写 | 平台 | gateway 地址 → 账号 → 设备命名；**跳过即单机模式**（游客态为默认） |

**复用度估算**：14/15 页面以共享包或参考重写覆盖，组件级复用 ≥ 70%（G2 达标；置信度：中）。V2 新增收益：Agent/技能/MCP/编排页与 web 同源同构，仅数据源切换。

### 6.3 状态管理

沿用 Redux Toolkit；新增 `desktopSlice`：连接模式、引擎状态、工作区列表、同步水位、本地/云端会话合并视图、权限白名单。会话运行态仍置于页面级（与 web 一致），持久化由引擎 H2 承担。

---

## 七、桌面运行时设计（gewu-desktop-runtime）

### 7.1 模块定位与依赖

```
gewu-platform (Maven 多模块，仓库根 pom 增加模块)
└── gewu-desktop-runtime/            # 新增 Java 模块（估算 2.5~3.5k 行）
    ├── pom.xml                      # 依赖: gewu-agent-engine, gewu-common(协议DTO下沉后),
    │                                #       spring-boot-starter-web, h2, (可选)micrometer
    └── src/main/java/com/gewu/desktop/
        ├── DesktopRuntimeApplication.java     # Spring Boot 宿主
        ├── endpoint/                          # 引擎 API Profile 控制器（§10.1）
        │   ├── DesktopChatController.java     #   POST /ai/chat, /ai/chat/stream
        │   ├── DesktopSessionController.java  #   /ai/sessions CRUD + messages
        │   ├── DesktopAgentController.java    #   /agents CRUD + tools/skills 挂载
        │   ├── DesktopSkillController.java    #   /skills CRUD
        │   ├── DesktopMcpController.java      #   /mcp-servers CRUD + tools
        │   ├── DesktopOrchestrationController.java # /orchestration 图/执行/SSE
        │   ├── DesktopApprovalController.java #   /approvals（本地授权流）
        │   └── DesktopAdminController.java    #   /engine/health /engine/shutdown /engine/config
        ├── adapter/                           # 桌面 SPI 适配集（§7.3）
        ├── tool/                              # 工作区工具集（§7.4，@ToolProvider）
        ├── security/                          # PathGuard / CommandValidator / 临时令牌过滤器
        └── store/                             # H2 schema（Flyway 随模块）
```

**依赖原则**：engine 保持零改动引用（引擎仓库内不出现任何 `desktop` 字样）；desktop-runtime 依赖 engine 的公开门面（AgentEngine/OrchestrationEngine）与 SPI 接口，不依赖其内部实现类。若引擎确需桌面化微调，一律以 SPI 或 `agent.engine.*` 配置项形式回流引擎（见 §10.3 E 系列）。

### 7.2 启动流程与配置

```
Electron 启动 → EngineSupervisor.spawn(java [jlink-runtime] -jar desktop-runtime.jar)
  stdin: {port, ephemeralToken, secrets: {modelKeys}, appVersion, logLevel}
  → Spring Boot 启动（CDS 优化，目标 ≤2.5s）
  → 绑定 127.0.0.1:{port}，注册临时令牌过滤器
  → 引擎自动装配（AgentEngineAutoConfiguration），桌面 SPI Bean 覆盖 NoOp 默认
  → Flyway(H2) 建表 → GET /engine/health 上报 ready → 渲染层 engine:status=ready
```

配置来源优先级：stdin 引导配置 > `application.yml`（desktop 内置默认）> 工作区 `.gewu/engine.yml`（项目级覆盖，如 maxToolRounds、历史条数、模型偏好）。引擎侧可配置化依赖 D-10 修复（§10.3 E2）。

### 7.3 桌面 SPI 适配集（引擎 16+ SPI 的本地接线）

| 引擎 SPI | 桌面实现 | 说明 |
|----------|----------|------|
| PersistenceService | DesktopPersistenceService（H2） | Agent/工具配置/执行记录本地读写；表结构对齐平台语义以便同步 |
| SessionContextService | DesktopSessionContextService | 本地历史构建（默认 50 条，可配）；H2 读取 |
| LlmProvider / LlmClient | DesktopLocalLlmProvider + DesktopProxyLlmProvider | 双源：本地 Key 直连（引擎 OpenAiCompatibleClient）/ 协同模式平台 LLM 代理；密钥自 stdin 引导注入，仅驻内存 |
| PermissionService | DesktopPermissionService | 三级权限模式仲裁（READ_ONLY / WORKSPACE_WRITE / FULL_AUTO）+ alwaysAllow 会话白名单（H2 kv）；未决时经 DesktopHitlGateway 走授权弹窗 |
| HitlGateway | DesktopHitlGateway | requestApproval → SSE `approval_required` 推送渲染层弹窗 → POST /approvals/{id} 恢复挂起 Mono（与平台 DbHitlGatewayAdapter 同构，30min 超时自动拒绝对齐） |
| AuditService | DesktopAuditService | 本地审计（H2 audit 表，含哈希链列，为上送预留） |
| SandboxExecutor | LocalDockerSandboxExecutor（ADR-008） | docker CLI 封装；无 Docker 降级禁用 + UI 外显；协同模式可回退云端 |
| McpServerConfigSource | DesktopMcpConfigSource | 本地 MCP 配置（工作区 .gewu/mcp.json + H2）；协同模式合并平台 MCP 清单（stdio 可本地化拉起） |
| MemoryStore / MemoryRouter | DesktopMemoryStore（v1 关键词/TF-IDF，H2） | v2 演进：本地 ONNX 嵌入（bge-small-zh，按需下载 ~90MB）|
| PolicyService / TraceService / MetricService | 桌面简化实现 | PolicyService=本地策略文件（协同模式可接收平台策略包，P4）；Trace=本地日志；Metric=H2 聚合供仪表盘 |
| ApiKeyDecryptor | DesktopApiKeyDecryptor | 本地模型 Key 以 SM4 加密存储于主进程 SecretVault（safeStorage 外层），stdin 注入时为明文仅驻内存 |
| PerceptionEngine / 复杂度路由 / BudgetController | 引擎内置（NoOp 感知 + 规则路由 + 四维预算） | 免费获得；感知的 Wenshi 桥接为平台侧特有 |
| ResponseCache / ModelSelector | 桌面按需 | 语义缓存 v1 不启用（无向量基础设施）；ModelSelector 直通 |

### 7.4 工作区工具集（@ToolProvider，引擎工具体系原生承载）

工具实现为 Spring Bean（`@ToolProvider`），由引擎 ToolRegistry 自动收集、ToolExecutor 五段管线（安全链→权限→分派→截断→审计）统一执行：

| 工具 | 实现（Java） | 安全约束 |
|------|--------------|----------|
| `fs.read / fs.write / fs.edit / fs.list / fs.glob / fs.grep` | java.nio + 正则/流式扫描 | PathGuard（realpath + 符号链接逃逸检测 + 工作区根约束）；写操作经授权 + diff |
| `shell.exec` | ProcessBuilder（超时/工作目录/环境白名单/进程组清理） | CommandValidator（移植平台沙箱 CommandValidator 16 条黑名单规则为共享规则文件）+ 授权 |
| `git.status/diff/log` | ProcessBuilder 封装 git CLI | 只读自动放行 |
| `web.search` | 可插拔：SearXNG URL（用户自建/协同平台实例）或 DDG 端点 | SsrfValidator（引擎自带）生效 |
| `project.context` | AGENTS.md → rules/*.md → .gewu/skills/*.md 装载（兼容本仓约定） | 只读 |
| `code_execute` | 经 SandboxExecutor SPI（本地 Docker / 云端回退） | 引擎安全链 CodeScannerCheck（18 Python + 16 Shell 模式）自带生效 |
| MCP 工具 | 引擎 StdioMcpClient / SseMcpClient | MCP 工具粒度授权（首用授权） |
| HTTP 工具（平台配置型） | 引擎原生 HTTP 分派 | SsrfValidator 主机白名单 |

**与 V1 的本质差异**：工具不再需要 TS 重写——平台 DB 配置型工具（http/mcp/code_execute）与本地代码型工具（@ToolProvider）在桌面运行时内**同一管线**执行，六层安全链完整生效（V1 本地仅能实现子集）。

### 7.5 智能体能力在桌面的运行形态（单机即全量）

| 能力域 | 单机形态运行方式 |
|--------|------------------|
| ReAct 流式执行 | 引擎 ReactAgentExecutor 原样运行（≤10 轮、流式工具调用累积、STATUS 起始事件） |
| 预算控制 | BudgetController 四维（Token/时间/成本/轮次）L1-L3 配额，本地 H2 记账 |
| 多 Agent 编排 | OrchestrationEngine 四模式 + AutonomousExecutor + AntiRunawayGuard 本地运行；HUMAN 节点经 DesktopHitlGateway 弹窗审批 |
| 记忆 | DesktopMemoryStore v1（关键词检索）→ v2（本地嵌入向量，按需下载模型） |
| HITL | 本地授权弹窗（工具级）+ 编排审批（任务级），统一审批中心 |
| 审计 | 本地 H2 审计日志（含哈希链列）；协同模式批量上送平台审计链 |

> 注意：引擎已知简化项（ReflexionRuntime 简化、DualSystemRouter 未接线、编排部分节点类型占位）在桌面形态与平台形态**同等存在**——这不是桌面引入的损失，而是引擎现状；其补齐由平台侧统一推进，桌面自动受益。

---

## 八、平台接入与协同设计

### 8.1 接入原则（V2 重新表述）

- **单机为基线，协同为叠加**：所有协同功能均为"增量接线"，断网/未登录时桌面完整可用；
- **统一入口**：协同流量全部经 gewu-gateway :8080（JWT 校验 → 限流 → 熔断），与 Web 端同构，不新开端口/协议；
- **认证复用**：`/auth/login` + JWT（access 30min / refresh 7d）；token 存主进程 SecretVault（safeStorage），自动刷新；登录请求携带 `X-Device-Id`（设备注册，P2 平台侧仅记录）；
- **引擎同一性红利**：桌面与平台的会话/消息/Agent/执行记录**schema 语义对齐**（同一引擎的领域对象），同步层退化为字段搬运 + client_id 幂等去重。

### 8.2 协同能力矩阵

| 协同场景 | 机制 | 平台侧依赖 | 阶段 |
|----------|------|-----------|------|
| 共享模型配置 | DesktopProxyLlmProvider → LLM 代理 | N1 | P2 |
| Agent/技能/MCP 资源同步 | 平台列表端点 + ETag 本地缓存合并；平台 Agent 可"导入本地"（含工具本地可用性检测标注） | 无改动（N6 可选优化） | P2 |
| 会话上行（本地→平台） | 批量同步 H2 会话（client_id 幂等） | **D-3 修复（N3）** + N2 同步端点 | P2 |
| 会话下行（平台→本地） | 增量拉取，可"接续为本地会话"（云端会话上下文注入本地引擎继续执行） | N2 | P2 |
| 执行记录上报 | 本地执行记录复用 `/agents/executions` create/complete/fail 上送 | 无改动 | P2 |
| 团队协作会话 | 订阅 `GET /sse/sessions/{id}`（message/approval_required 命名事件）+ 发消息 API | **N4：SSE 广播 Redis Pub/Sub 化**（多副本前置） | P3 |
| 远程 HITL 审批 | approval_required 推送 → 桌面审批中心 → `/approvals` 批准/驳回 | N4 | P3 |
| 云端沙箱回退 | 本地 Docker 缺失或用户显式选择 → `/sandboxes`（经网关至 :8082） | 无改动 | P3 |
| 云端执行路由 | 用户显式选择"云端执行"的 Agent → 经网关调 `/ai/chat/stream`（渲染层透明） | 无改动 | P3 |
| 用量上送 | 直连模式批量上报本地 usage | N2 附带 | P2 |
| 审计上送 | 本地哈希链批量上送平台审计链 | 审计接口扩展（N8） | P4 |
| Agent 发布到市场 | 本地 Agent 一键发布平台市场（走平台既有发布/审核流） | 无改动（复用 market 端点） | P4 |
| 策略包下发 | 平台 PolicyService 下发企业权限策略覆盖本地默认 | 策略端点扩展 | P4 |

### 8.3 会话同步协议（上行示例，同 V1 机制）

```
桌面(desktop-runtime)                平台(DesktopSyncController)
    │ POST /api/v1/desktop/sync/sessions       │
    │ {sessionId, messages:[{clientId, seq,    │
    │   role, content, metadata}], watermark}  │
    ├─────────────────────────────────────────►│
    │                                          │ 幂等: client_id 查重落库
    │                                          │ seq 冲突: 平侧重排
    │◄─────────────────────────────────────────┤
    │ {code:10000, data:{accepted, skipped,    │
    │  nextWatermark}}                         │
```

冲突策略：消息级 client_id 幂等去重；会话元信息 LWW（updated_at 毫秒级）；字段级合并不做（预留平台 parentId 分叉机制）。下行"接续"语义：拉取云端会话消息 → 注入本地引擎 SessionContextService 上下文 → 本地继续执行 → 后续消息双向同步。

---

## 九、能力边界清单

> 本章直接回应设计前提："单机保障完整智能体能力；只有平台才能具备、不适合单机的能力不集成"。判定标准：**能力是否在本质上依赖服务端状态或多方参与**。依赖者不进入单机形态（桌面仅在协同形态作为消费端使用它）；不依赖者全部由引擎本地承载。

### 9.1 单机形态完整具备的能力（引擎本地承载）

流式对话与思考过程、本地工具全集（fs/shell/git/search/项目上下文）、本地 Docker 沙箱、本地 stdio MCP、Agent 定义与管理（本地库）、技能库（本地）、**多 Agent 编排（四模式 + 自主目标）**、四维预算控制、复杂度路由、本地记忆（v1/v2）、本地 HITL 授权、本地审计（含哈希链）、会话管理（历史/搜索/持久化）、多厂商模型配置（本地 Key SM4 加密）、用量统计（本地仪表盘）。

### 9.2 仅协同形态提供（平台固有，桌面作为消费端）

| 能力 | 为何平台固有 | 桌面形态的替代/降级 |
|------|--------------|---------------------|
| 多用户协作会话（消息广播/成员管理） | 需要服务端连接表与多方在线 | 本地会话（单机语义本就无协作对象） |
| Agent/技能市场的发布与审核流 | 需要平台审核与分发基础设施 | 本地技能目录；协同后可发布/安装 |
| 云端沙箱池 | 服务器 Docker 资源池 | 本地 Docker；缺失时工具直执行强授权 |
| 集中审计存证与哈希链校验 | 需要可信第三方存证 | 本地审计日志（自带哈希链列，协同后上送） |
| 企业共享模型配置中心 | 企业统一管控 Key 的服务端职责 | 本地模型配置（个人自有 Key） |
| 集中用量治理与预算下发 | 跨用户聚合与管控 | 本地用量统计（单用户语义） |
| Wenshi 认知推理引擎（Planner/SolverRouter/Critic + 联网验证 + 文件产物） | 依赖集群向量库（pgvector）与 SearXNG 基础设施，属平台应用层引擎 | 桌面以 `web.search` 工具 + 引擎 ReAct 覆盖联网检索场景；协同模式可路由至云端执行获得完整 Wenshi |
| 多副本高可用 / HPA | 部署形态属性 | 单机无此语义 |

### 9.3 明确不做（非能力，产品边界）

- 桌面端不内置平台管理后台（用户/角色/菜单/组织管理）——企业管理在 Web 端完成；
- 桌面端不承载平台项目的文档/需求域编辑（协同模式只读链接跳转 Web 端）；
- 不做移动端（ 远期由 Web 响应式覆盖）。

---

## 十、接口契约设计

### 10.1 引擎 API Profile（desktop-runtime 暴露，与平台契约同构）

| 端点 | 与平台关系 | 桌面差异 |
|------|-----------|----------|
| `POST /ai/chat`、`POST /ai/chat/stream` | 同构（ChatRequest / ChatStreamEvent SSE / `[DONE]`） | legacy 引擎路径直接执行；wenshi 路由不存在（单机无该引擎） |
| `/ai/sessions` CRUD + messages | 同构 | 本地 H2 |
| `GET /ai/models` | 同构 | 本地 Provider 清单 |
| `/agents` CRUD + tools/skills 挂载 | 同构 | 本地库 |
| `/skills` CRUD | 同构 | 本地库 + 工作区目录扫描 |
| `/mcp-servers` CRUD + tools 发现 | 同构 | stdio 本地管理 |
| `/orchestration` 图 CRUD/execute/stream/goals | 同构 | 本地执行 |
| `/approvals` pending/approve/reject | 同构 | 本地授权流（HitlGateway 桥） |
| `/audit`、`/usage` | 简化 | 本地查询 |
| `/engine/health`、`/engine/shutdown`、`/engine/config` | **桌面特有**（管理面） | 临时令牌保护 |

Profile 集合以 OpenAPI 描述固化于 `@gewu/agent-ui/protocol`（与平台 `/v3/api-docs` 同源校验）；渲染层 api-client 对两个 baseURL 使用同一类型化客户端。

### 10.2 平台侧新增/改造清单（输入给平台排期）

| # | 类型 | 端点/变更 | 说明 |
|---|------|-----------|------|
| N1 | 新增 | `POST /api/v1/ai/llm-proxy/chat/stream` | LLM 代理：`{provider, model, messages, tools, stream, clientRequestId}` → SSE 归一化 LlmChunk；JWT + 限流 + 服务端预算 + usage 记账 |
| N2 | 新增 | DesktopSyncController：`POST /desktop/sync/sessions`（上行）、`GET /desktop/sync/sessions?since=`（下行）、`POST /desktop/usage`（用量批量） | 会话/用量同步；前置 D-3 |
| N3 | 改造 | session_message 写路径 clientId 查重 | 平台 P0 债务 D-3，桌面为第一消费方 |
| N4 | 改造 | SseEventManager → Redis Pub/Sub（DragonflyDB） | 多副本广播，P3 协作/HITL 前置 |
| N5 | 复用 | `/auth/*`、`/ai/chat/stream`、`/ai/sessions`、`/agents/*`（含 market）、`/skills/*`、`/mcp-servers/*`、`/approvals/*`、`/sse/sessions/{id}`、`/sandboxes/*`、`/agents/executions/*`、`/stats` | 零改动 |
| N6 | 可选 | `GET /api/v1/desktop/resources/manifest` | 资源聚合快照 + ETag（P2 可先用现有列表端点） |
| N7 | 可选 | ResultCode 错误码域 18xxx 分配 desktop sync | 遵循 5 位编码规范 |
| N8 | 可选 | 审计上送端点（接收桌面哈希链增量） | P4 |

### 10.3 引擎层前置修复（平台 P0/P2 债务，桌面阻塞项）

| # | 债务 | 对桌面的影响 | 阶段 |
|---|------|--------------|------|
| E1 | **D-2**：Message 缺 toolCalls 字段（多轮工具调用请求体协议缺陷） | 本地模式多轮工具对话对严格厂商 400——**单机核心场景阻塞** | P1 阻塞 |
| E2 | **D-10**：LLM 超时/预算参数硬编码（requestTimeout 120s 未消费） | 桌面需要按 profile 配置（交互型场景超时应更短） | P1 |
| E3 | D-3：seq 原子化 + client_id 幂等 | 会话同步前置（= N3） | P2 |
| E4 | 引擎构建产物发布（Maven 坐标 / GitHub Packages / 内部仓） | desktop-runtime 跨模块依赖与桌面构建管线可复现性 | P0 |
| E5 | 协议 DTO 下沉：ChatStreamEvent/ChatRequest/AgentChunk 映射器移至 gewu-common（或新建 gewu-protocol 轻模块） | desktop-runtime 与 gewu-interface 共用同一协议实现，杜绝手抄 | P0 |
| E6 | D-1：引擎零测试 | 桌面将引擎带向不可控的终端环境，测试债紧迫度升级（详见 R1/R2） | P1 起持续 |

### 10.4 IPC 契约

见 §5.3；zod schema 存于 `packages/agent-ui/protocol/ipc`。

---

## 十一、安全架构设计

### 11.1 安全边界与威胁模型

| 威胁 | 缓解措施 |
|------|----------|
| 渲染层 XSS → 伪造本地 API 调用 | 渲染进程 sandbox + contextIsolation；本地引擎要求临时令牌（每次启动更换，仅经 preload 交付渲染层）；引擎端点 CORS 仅允许 app 源；CSP `connect-src` 白名单（127.0.0.1 + gateway 域） |
| 本机其他进程未授权访问引擎端口 | 127.0.0.1 绑定 + 随机端口 + 临时令牌（X-Engine-Token）；令牌经 stdin 交付引擎，不落盘 |
| 提示注入诱导危险工具调用 | 引擎安全链全量生效（PromptInjectionDetector 8+5 正则、SchemaValidator、CodeScannerCheck）+ 桌面 PathGuard + CommandValidator + PermissionService 三重授权；写操作强制 diff 预览 |
| 工作区路径逃逸（符号链接） | PathGuard realpath 二次校验前缀（桌面工具层，与引擎安全链叠加） |
| 本地密钥泄露 | SecretVault（safeStorage/OS keychain）托管；stdin 注入仅驻引擎内存；引擎堆转储禁用（-XX:-HeapDumpOnOutOfMemoryError）；不入 H2/日志 |
| 平台 JWT 被窃 | 仅存 SecretVault；渲染层经主进程代理认证请求，不落 localStorage（与 web 差异点） |
| 引擎 API Profile 被滥用（本机恶意进程持有令牌场景） | 令牌内存态 + 进程级隔离；敏感管理端点（shutdown/config）二次确认；审计记录含调用来源 |
| MCP 服务器供应链风险 | 本地 MCP 安装确认命令行；MCP 工具首用授权；来源记录入审计 |
| 自动更新投毒 | electron-updater + electron-builder 签名校验（latest.yml 哈希）；引擎 jar 同样纳入签名校验清单 |
| 敏感代码外泄 | 单机模式代码不出本机（LLM 直连为唯一外发，用户自管 Key 与厂商）；协同模式可按项目禁用代理/同步（项目级开关） |

### 11.2 权限三级模式（对标 ZCode，PermissionService 承载）

| 模式 | 只读工具 | 工作区写/白名单外命令 | 黑名单命令 |
|------|----------|----------------------|-----------|
| READ_ONLY（默认） | 自动 | 一律弹窗 | 一律拒绝 |
| WORKSPACE_WRITE | 自动 | 弹窗 + alwaysAllow 会话白名单 | 一律拒绝 |
| FULL_AUTO | 自动 | 自动（审计记录） | 拒绝 + 告警 |

企业管控场景：协同模式接收平台策略包覆盖本地默认（P4）。

### 11.3 审计与合规

- 本地全量工具/执行审计入 H2（含哈希链列，链头本地生成）；P4 批量上送平台审计链（SHA-256 哈希链防篡改校验在平台完成）；
- 传输：协同流量强制 TLS；本地回环免 TLS（令牌保护）；
- 日志 PII 脱敏（引擎 OutputSanitizer 正则生效）。

---

## 十二、部署与分发设计

### 12.1 构建与打包

| 项 | 方案 |
|----|------|
| 构建 | 前端：electron-vite + Vite library mode（共享包）；运行时：`mvn -pl gewu-desktop-runtime package` 产出 fat jar → 复制进桌面构建资源 |
| JVM 定制 | **jlink**（jdeps 计算模块集：java.base/java.sql/java.naming/java.management/java.net.http/jdk.unsupported/jdk.crypto.ec 等）+ AppCDS（Spring Boot 启动加速）；产物 ~50MB |
| 打包 | electron-builder：Windows NSIS / macOS dmg（arm64+x64）/ Linux AppImage+deb |
| 信创 | P3 交付 linux-arm64（毕昇 JDK 21 可用）；loongarch 依赖龙芯 OpenJDK 21 成熟度，列观察项（H2 纯 Java 无原生库障碍，主风险在 JVM 与 Electron 社区包） |
| 体积预算 | 安装包 ≤ 180MB（Electron 底座 ~85 + jlink JRE ~50 + jar ~35 压缩后）；安装后 ≤ 300MB |
| 引擎 jar 热替换 | 运行时 jar 与 Electron 包解耦存放，为"引擎独立更新"预留（P4） |

### 12.2 升级机制（V2.2 细化：三层独立升级）

V2 架构下桌面端存在三个可独立演进的层，对应三条升级路径：

| 层 | 内容 | 升级方式 | 频率预期 |
|----|------|----------|----------|
| **L1 应用整包** | Electron 壳 + 渲染层 + jlink JRE + 引擎 jar（捆绑） | electron-updater 差量更新（NSIS blockmap / dmg / AppImage） | 应用迭代节奏（月级） |
| **L2 引擎运行时** | desktop-runtime.jar（含 gewu-agent-engine） | 独立下载替换：EngineSupervisor 校验签名后替换 jar、重启引擎生效；新 jar 启动失败自动回退上一版 | 引擎迭代节奏（周级，高频） |
| **L3 资源与配置** | Agent/技能/MCP 清单、模型配置、企业策略包 | 协同形态经平台资源同步拉取（数据驱动，无安装包）；单机形态本地管理 | 随平台内容 |

**L1 升级流程**：应用启动时 + 每 4 小时定时检查更新源 `latest.yml` → 后台静默下载 → 就绪提示 → 用户确认重启安装（可设置"退出时自动安装"）；stable / beta 双通道（设置页可选）。

**安全与回滚**：全部产物签名（Windows Authenticode / macOS 公证 / Linux 包 + 引擎 jar 的 sha512 清单签名）；EngineSupervisor 在 spawn 前校验引擎 jar 签名，失败拒绝启动并告警。回滚：L1 由 electron-updater 版本降级机制（向更新源发布低版本号）；L2 保留上一版 jar 自动回退；L3 天然可重拉。

**强制升级策略**：协同握手版本协商（§12.5）发现桌面 API Profile 版本过旧时，锁定协同功能并引导升级——**单机功能始终可用**（遵循"协同是叠加"原则）；单机形态不存在强制升级。

### 12.3 CI/CD

Jenkinsfile 新增 `desktop` stage：pnpm build/test → mvn desktop-runtime package → electron-builder 三平台 → 签名 → MinIO 上传 + latest.yml。路径过滤触发（`gewu-desktop/**`、`packages/**`、`gewu-desktop-runtime/**`）。

### 12.4 分发渠道（V2.2 细化）

```
开发者 ──下载/更新──► MinIO 静态桶 gewu-desktop-releases（主渠道）
        │               └─ /{channel}/{version}/{os-arch}/安装包 + latest.yml
        │登录(协同)──► gewu-gateway :8080 ──► 平台集群(K8s)
        └LLM直连──► LLM 厂商(单机模式, 用户自有 Key)

渠道补充：① Web 端下载页（OS 检测自动推荐安装包，可选）
         ② 企业内网批量分发（P4）：MSI/麒麟软件商店/UOS 应用仓库适配
私有化交付：集群 + 更新桶同域内网，桌面配置「内网更新源 + 内网网关地址」；
           提供全量离线安装包与历史版本留存（内网隔离客户刚需）
```

**版本策略**：桌面版本 SemVer 独立演进（`desktop 1.2.0`），安装包 metadata 记录所含引擎版本（`engine 2.1.0`）——二者解耦发版，L2 通道可单独推进引擎；CI 以 git tag 触发三平台构建 → 签名 → MinIO 上传 → latest.yml 更新（stable）；beta 通道自 main 分支自动构建。

### 12.5 平台迭代 → 桌面影响的变更传播矩阵（V2.2 新增）

判定规则一句话：**平台迭代是否变更"契约"（协议 DTO / API Profile / 引擎 SPI）——不变则桌面零改动，变则按矩阵传导**。

| 平台变更类型 | 桌面是否改动 | 改动内容 | 传导载体 |
|--------------|:------------:|----------|----------|
| Web 业务页面/业务域功能迭代（项目、需求、工作流定义等） | ❌ | —（桌面非 Web 全量镜像，§9.3） | 无 |
| 引擎内部修复/优化/新增能力（协议不变） | ❌ 壳零改动 | 引擎 jar 重打包 | **L2 通道**（V2 核心红利：同一引擎代码，平台演进自动惠及桌面） |
| ChatStreamEvent 新增事件类型 | 🟡 渲染层小改 | 共享包 protocol + 新事件渲染（旧版桌面**忽略未知事件**前向兼容） | L1/L2 + 共享包 |
| API Profile 端点新增/增强 | ❌（不消费即无感） | 可选跟进 | 无强制 |
| 协议 DTO 破坏性变更 | 🔴 需联动发布 | 协议包 + 双端 + 契约测试同步 | 平台与桌面协调发版 + 版本协商 |
| 引擎 SPI 变更 | 🟡 | desktop-runtime 适配层（Java 编译期即暴露） | L1/L2 |
| 平台部署拓扑演进（多副本/Redis 化等） | ❌ | 桌面为消费端，契约不变即无感 | 无 |
| Result 信封/错误码新增 | ❌ | 可选更新错误码文案 | 随包 |

**版本协商握手**（协同登录时执行）：`GET /api/v1/platform/info` 返回 `{apiVersion, engineVersion, minDesktopVersion}`；桌面比对自身 API Profile 版本，产出三态结论——**兼容**（正常）/ **部分兼容**（降级提示，不可用协同项明示）/ **不兼容**（锁定协同功能 + 升级引导，单机不受影响）。协议演进纪律：ChatStreamEvent 新增事件一律"追加不修改"，DTO 字段只增不改语义，破坏性变更须走版本协商。

---

## 十三、非功能需求（V2 修订）

| 类别 | 指标 | 目标值 |
|------|------|--------|
| 性能 | 冷启动 → 工作区可用 | ≤ 4s（含引擎启动，splash 遮盖） |
| | 引擎启动（AppCDS 优化后） | ≤ 2.5s |
| | 对话首 token（单机直连厂商） | ≤ 1.5s P95（不含模型推理） |
| | 工具调用（fs.read 1MB 内） | ≤ 100ms |
| 资源 | 空闲内存（四进程合计，JVM -Xmx384m） | ≤ 800MB |
| | 安装包 / 安装后 | ≤ 180MB / ≤ 300MB |
| 可用性 | 引擎崩溃恢复 | 自动重启 ≤ 3 次/会话；H2 状态不丢 |
| | 断网 | 单机模式全功能（LLM 直连除外）；协同模式优雅降级至单机能力 |
| 可靠性 | 同步幂等 | 消息级 client_id 去重，重放零副作用 |
| 安全 | 密钥落盘 | 零明文（safeVault + SM4 双层） |
| 兼容 | OS | Windows 10+ / macOS 12+ / Ubuntu 20.04+（glibc≥2.31）；linux-arm64（P3） |

> 与 V1 差异：体积与内存目标放宽（JVM 引入），换取能力平价——这是本架构的核心权衡，明确记录。

---

## 十四、风险评估

| # | 风险 | 等级 | 影响 | 缓解措施 |
|---|------|------|------|----------|
| R1 | **引擎 D-2 协议缺陷带病嵌入**：Message 缺 toolCalls 序列化，单机多轮工具调用对严格厂商 400 | 🔴 高 | 单机核心场景不可用 | E1 列为 Phase 1 阻塞项（本就是平台 P0 修复项，顺势推进）；修复后 OpenAI/DeepSeek/智谱三家多轮工具回归 |
| R2 | **引擎零测试债被终端化放大**（D-1）：桌面将引擎带入不可控的本地环境（各类厂商 API、文件系统、MCP 实现） | 🔴 高 | 单机体验回归无防护网 | Phase 0 Spike 先行验证 NoOp 启动；P1 起引擎核心包测试 ≥70% 与桌面开发并行推进（与平台短期计划合流） |
| R3 | **JVM 体积/内存超预期**：jlink 模块裁剪不彻底、Spring Boot 依赖拉入过重 | 🟠 中 | 安装包 >180MB、启动 >4s | Phase 0 体积 Spike（jlink 模块清单 + CDS 实测）；jlink 产物不达标则降级方案：完整 JRE + 更激进压缩，或裁剪 desktop-runtime 依赖（micrometer 可选化） |
| R4 | **desktop-runtime API Profile 与平台漂移**（协议 DTO 未下沉，两处实现） | 🟠 中 | 渲染层双端行为不一致 | E5 协议 DTO 下沉为强制前置；Profile 契约测试（同一 fixture 打双端断言） |
| R5 | **团队带宽**：平台 P0 债务 + 桌面三线（共享包/桌面壳/desktop-runtime）并行 | 🟠 中 | 双线进度风险 | 严格分期（Phase 0 全部 Spike 前置去险）；desktop-runtime 与引擎修复同人力池（同 Java 栈，切换成本低） |
| R6 | LLM 代理增加平台负载/滥用 | 🟡 低 | 服务端成本 | 网关限流复用 + 代理端点独立预算上限 + clientRequestId 幂等 |
| R7 | Electron/Chromium CVE | 🟡 低-中 | 安全合规 | 季度版本跟进策略 + Dependabot；渲染层 sandbox 全开 |
| R8 | 信创交付（loongarch JVM/Electron 生态） | 🟡 低 | 信创包延期 | arm64 先行（毕昇 JDK）；loongarch 列观察项，H2 纯 Java 已消除一类原生库风险 |
| R9 | 引擎既有简化项被用户感知为桌面缺陷（编排节点占位等） | 🟡 低 | 口碑 | 产品文档诚实标注引擎能力现状；桌面发布说明与引擎版本对齐 |

**置信度说明**：R1/R2 基于引擎源码实读（D-2 协议缺陷、引擎零测试均为已核实事实），等级评定含主观成分（置信度：中高）。V1 的 R1"双引擎行为漂移"风险在 V2 中被**结构性消除**（同一代码），不再列示。

---

## 十五、实施路线图

> 相对周期（1 名全时开发估算），预留 10% 调整空间。V2 路线图核心变化：**引擎可行性 Spike 全部前置到 Phase 0**；能力完整性按域分阶段兑现，但每一阶段交付的都是"可独立使用的完整单机产品"（增量式全量，而非先 lite 后 full）。

### Phase 0：可行性 Spike + 共享包抽离（1.5 周）

- **Spike-A 引擎单机启动**：验证 NoOp 默认装配可独立启动 + 最小 chat/stream 端点跑通 + jlink 体积实测（R3 去险）；
- **Spike-B 协议 DTO 下沉评估**（E5）：ChatStreamEvent 等移入 gewu-common 的改动面评估；
- 共享包抽离：pnpm workspace + `packages/agent-ui`（protocol/api-client/process/chat-ui/ui/theme），web 最小改动接入；
- 交付物：启动 PoC + 体积报告 + 共享包 + IPC/API 契约草案。

### Phase 1：单机核心（4-5 周）

- 平台侧：E1（D-2 toolCalls 修复）、E2（超时配置化）、E4（引擎产物发布）；
- desktop-runtime MVP：Chat/Session/Model 端点 + DesktopPersistenceService(H2) + DesktopLocalLlmProvider + DesktopPermissionService/HitlGateway（授权弹窗流）+ 工作区工具集（fs/shell/git/context）+ PathGuard/CommandValidator；
- 桌面壳：窗口/工作区选择器/EngineSupervisor/SecretVault/终端面板/文件树/diff；
- 渲染层：聊天工作台 + Agent 管理（本地）+ 设置 + 权限弹窗（共享包复用）；
- 交付物：**单机完整对话+工具闭环安装包**（G1 核心达成：单机即可作为日常 Agent 工具使用）。

### Phase 2：能力补全（4 周）

- desktop-runtime：本地 Docker 沙箱执行器（ADR-008）、MCP 管理（引擎 StdioMcpClient）、**编排本地可用**（Orchestration 端点 + 编排工作台页）、Agent/技能管理完整化、记忆 v1、本地用量仪表盘；
- 平台侧：N1 LLM 代理、N3（D-3 幂等）、N2 同步端点；
- 桌面侧：DesktopProxyLlmProvider（共享模型）、会话上行/下行同步、执行记录上报、资源清单合并；
- 工作区上下文加载器完善（AGENTS.md/rules/skills 装载进系统提示词与 project.context 工具）；
- 交付物：单机全能力版 + 协同形态基础（模型共享 + 会话同步）。

### Phase 3：协同完整化与分发（4 周）

- 平台侧：N4 SSE 广播 Redis Pub/Sub 化（与平台多副本改造合并）；
- 桌面侧：协作会话参与、远程 HITL 审批（统一审批中心）、云端沙箱回退、云端执行路由、平台资源导入（Agent/技能/MCP）；
- 分发：三平台签名 + electron-updater + Jenkins desktop stage + linux-arm64 信创包；
- 交付物：正式版发布（G3/G5 达标）。

### Phase 4：远期演进

- 记忆 v2（本地 ONNX 嵌入，按需下载 bge-small-zh）、审计上送平台哈希链（N8）、本地 Agent 发布平台市场、企业策略包下发、多端会话移交（gewu:// 深链）、引擎 jar 独立更新通道、Monaco 只读代码浏览。

### 里程碑依赖

```
Phase 0 ──► Phase 1 ──► Phase 2 ──► Phase 3 ──► Phase 4
 Spike去险     │            │            │
              ├─ E1/E2/E4(引擎修复+发布) ─┘
              └─ E5 协议DTO下沉
                           └─ N1/N2/N3(平台新端点+D-3)
                                        └─ N4(SSE Redis化)
```

---

## 十六、附录

### 附录 A：仓库目标结构

```
gewu-platform/
├── gewu-desktop-runtime/        # 新增 Maven 模块（§7.1 目录详图）
├── gewu-desktop/
│   ├── package.json             # pnpm workspace 成员
│   ├── electron-vite.config.ts
│   ├── electron/
│   │   ├── main/
│   │   │   ├── index.ts         # 窗口/托盘/单实例/生命周期
│   │   │   ├── ipc/             # §5.3 控制面通道
│   │   │   ├── engine/          # EngineSupervisor（spawn/心跳/重启/引擎jar签名校验）
│   │   │   ├── vault/           # SecretVault（safeStorage + SM4）
│   │   │   ├── terminal/        # TerminalManager（node-pty）
│   │   │   └── services/        # UpdaterService / DeepLinkService / ConfigService / ConnectionService
│   │   ├── preload/index.ts     # contextBridge 类型化 API
│   │   └── resources/
│   │       └── runtime/         # 构建期注入: jlink JRE + desktop-runtime.jar（签名）
│   └── src/                     # 渲染进程（React + Vite）
│       ├── app/                 # App Shell / desktopSlice / 命令面板
│       ├── connection/          # ConnectionManager（本地/平台 baseURL + 凭据注入）
│       ├── features/            # chat / agents / skills / mcp / orchestration / terminal /
│       │                        # editor(diff) / approval / sandbox / settings / usage
│       └── components/          # PermissionDialog / FileTree 等桌面专属组件
├── packages/
│   └── agent-ui/                # 共享包（§6.1）
└── gewu-web/                    # 渐进迁移引用共享包
```

### 附录 B：与既有文档/债务的关联索引（V2 修订）

| 关联项 | 位置 | 关系 |
|--------|------|------|
| 桌面端立项决策 | 分析报告 §9.3-6 | 本文档即落地设计 |
| **引擎可独立复用设计** | 分析报告 §2.3/§5.2（21 SPI + NoOp + 零 DB/Web 依赖） | **V2 架构基石**：一引擎两形态 |
| LLM 协议缺陷 D-2 / 硬编码 D-10 | 分析报告 §8 | 桌面 Phase 1 阻塞项（E1/E2） |
| 引擎零测试 D-1 | 分析报告 §8 | 桌面化使其紧迫度升级（R2），与平台短期计划合流 |
| client_id 幂等 D-3 | 分析报告 §6.6/§8 | 会话同步前置（E3/N3） |
| SSE 多副本广播 | 分析报告 §9.3-1 | Phase 3 前置（N4） |
| 双轨 API 封装 D-19 | 分析报告 §8 | 共享包 api-client 收敛 |
| 表现层三端规划 | docs/design/01/21/25 | 承接「桌面客户端 Electron 28+」 |
| Vite 时代组件 | git commit `6def6aa` | PermissionDialog/TerminalPanel/FileTreeNode 参考蓝本 |

### 附录 C：能力矩阵总览（V2 终版）

| 能力 | 单机形态 | 协同形态 |
|------|:--------:|:--------:|
| 流式对话/思考/工具时间线 | ✅ | ✅ |
| 本地文件/终端/项目上下文 | ✅ | ✅ |
| 工具安全链（引擎六层全量） | ✅ | ✅ |
| 本地 Docker 沙箱 | ✅（缺 Docker 降级） | ✅ + 云端回退 |
| MCP（本地 stdio） | ✅ | ✅ + 平台清单同步 |
| Agent 定义/管理/技能库 | ✅（本地库） | ✅ + 市场安装/发布 |
| **多 Agent 编排（四模式/自主目标）** | ✅（本地执行） | ✅ + 云端编排实例 |
| 四维预算/复杂度路由 | ✅ | ✅ |
| 记忆 | ✅ v1→v2 | ✅（平台 Wenshi 桥接增强） |
| HITL | ✅（本地授权/编排审批） | ✅ + 远程审批订阅 |
| 审计 | ✅（本地哈希链） | ✅ + 平台集中存证 |
| 模型多厂商 | ✅（本地 Key） | ✅ + 平台共享配置（代理） |
| 多用户协作会话 | ❌（语义不适用） | ✅ |
| 多端会话同步 | ❌ | ✅ |
| Wenshi 认知推理引擎 | ❌（以 web.search 工具近似） | ✅（云端执行路由） |
| 集中用量治理/策略下发 | ❌（本地版统计） | ✅（P4） |

---

*文档完 | V2.0 修订要点：单机=完整智能体能力（引擎嵌入），协同=资源接线叠加，平台固有能力不集成（§9.2 清单）| 下一步：① Phase 0 两个 Spike 立项 ② E1/E2/E4/E5 引擎侧排期确认 ③ 平台侧 N1/N2 新端点排期*
