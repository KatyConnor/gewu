# gewu-desktop 详细实施计划

> **文档编号**：EXE-DESKTOP-2026-Q4 | **版本**：V1.0 | **编制日期**：2026-09-09
> **状态**：🚀 待启动（计划基线已评审定稿）
> **架构基线**：`docs/design/42-gewu-desktop-architecture-design.md`（V2.2，"一引擎两形态"，ADR-001~008 已定稿）
> **角色**：项目管理与敏捷教练（角色10）+ 资深技术架构师（角色03，技术任务核定）
> **维护者**：架构组
> **估时口径**：1 人日 = 1 名合格开发的全时工作日；所有估算预留 10% 调整空间

---

## 目录

1. [计划概述与前提](#一计划概述与前提)
2. [里程碑总表与验收门禁](#二里程碑总表与验收门禁)
3. [Phase 0 详细任务（可行性去险 + 共享包）](#三phase-0-详细任务)
4. [Phase 1 详细任务（单机核心）](#四phase-1-详细任务)
5. [Phase 2 详细任务（能力补全 + 协同基础）](#五phase-2-详细任务)
6. [Phase 3 详细任务（协同完整化 + 分发）](#六phase-3-详细任务)
7. [平台侧配套任务清单](#七平台侧配套任务清单)
8. [依赖关系与关键路径](#八依赖关系与关键路径)
9. [风险登记册](#九风险登记册)
10. [质量门禁与测试策略](#十质量门禁与测试策略)
11. [进度跟踪机制](#十一进度跟踪机制)
12. [Phase 4 Backlog（远期）](#十二phase-4-backlog)

---

## 一、计划概述与前提

### 1.1 交付目标（承接设计文档 G1~G6）

| 里程碑 | 交付物 | 目标日期 |
|--------|--------|----------|
| M0 | Spike 结论（Go/No-Go）+ 共享包 `@gewu/agent-ui` | 2026-09-22 |
| M1 | 单机完整对话+工具闭环安装包（三平台内测版） | 2026-10-30 |
| M2 | 单机全能力版 + 协同基础（模型共享/会话同步） | 2026-11-26 |
| M3 | 正式版发布（协同完整 + 分发链路 + 信创 arm64） | 2026-12-23 |

> 日历口径：2026-09-14（周一）启动；国庆假期（10-01~10-07）不计工期已扣除；含 10% 缓冲后 M3 最迟滑至 2027-01 上旬。

### 1.2 人力假设与两种情景

| 情景 | 配置 | 桌面关键路径 | 平台配套任务 |
|------|------|--------------|--------------|
| **情景 A（基准）** | 桌面 1 名全时（TS+Java 双栈）；平台侧任务（PT 系列）由平台迭代人力池并行消化 | **14.5 周**（本计划排期） | 按 §七 窗口并行 |
| 情景 B（单人力包干） | 同 1 人兼做桌面与平台配套任务 | **~17 周**（追加 PT 系列约 12~15 人日串行） | 串行插入 |

**排期调整规则**：若采用情景 B，各里程碑顺延 PT 任务耗时（M1 +3d / M2 +5d / M3 +4d）。

### 1.3 启动前核实项（W37 第一天完成，防止基于过时事实排期）

| # | 核实项 | 原因 | 负责人 |
|---|--------|------|--------|
| V-1 | 引擎测试覆盖现状（`docs/plan/done/sprint2-engine-tests-done.md` 已建引擎测试，222 测试全绿基线） | 设计文档 R2 的紧迫度评估依赖实际覆盖范围 | 桌面开发 |
| V-2 | D-2（Message 缺 toolCalls）修复状态 | PT-E1 是否已完成直接影响 Phase 1 W2 排期 | 平台侧 |
| V-3 | 双引擎收敛后 chat 主链路行为（P0-2 已完成：AgentExecutionEngine 委托 ReactAgentExecutor） | desktop-runtime 端点实现以收敛后链路为准 | 桌面开发 |
| V-4 | 协议 DTO 现状（ChatStreamEvent/ChatRequest 引用面扫描） | PT-E5 下沉改动面评估输入 | 平台侧 |

### 1.4 前置条件（长周期项立即启动）

| # | 前置项 | 启动时点 | 说明 |
|---|--------|----------|------|
| P-1 | **代码签名证书采购**：Windows Authenticode（EV 或 OV）+ Apple Developer（Developer ID + 公证资质） | **W37（启动当日）** | 采购+审核周期 2~6 周，是 M3 分发签名的硬前置；EV 证书U盘/硬件令牌邮寄周期最长 |
| P-2 | 构建机准备：macOS（x64+arm64）、Windows、Linux（x64+arm64，毕昇 JDK 21） | W37 | electron-builder 三平台出包与 jlink 交叉验证 |
| P-3 | LLM 厂商测试 Key（DeepSeek/智谱至少各 1） | W37 | Spike-A 与 Phase 1 联调 |
| P-4 | 引擎产物 Maven 仓库坐标开通（内部 Nexus / GitHub Packages 二选一） | W37 | PT-E4 依赖 |

### 1.5 范围外（本计划不含）

Phase 4 Backlog 全部条目（§十二）、平台业务域功能迭代、视觉规范专项（另行立项）、移动端。

---

## 二、里程碑总表与验收门禁

| 里程碑 | 验收门禁（全部满足方可进入下一阶段） |
|--------|--------------------------------------|
| **M0**（09-22） | ① Spike-A：引擎 NoOp 装配独立启动 + 最小 chat/stream 跑通 + jlink JRE ≤ 60MB + 引擎启动 ≤ 3s；② Spike 结论评审通过（Go）；③ 共享包构建绿 + gewu-web chat 路径回归绿；④ 协议 DTO 下沉决策定稿；⑤ ADR-001 复决条件复查（Electron arm64 构建验证） |
| **M1**（10-30） | ① 单机 E2E：对话→工具调用→授权弹窗→diff 预览→执行→结果落库→应用重启后会话恢复；② 引擎安全链本地生效用例通过（路径逃逸/注入/命令黑名单）；③ 三平台安装包可安装、可启动、引擎子进程守护可用；④ 契约测试基线建立（Profile 双端 fixture）；⑤ 性能：引擎启动 ≤2.5s、首 token ≤1.5s（直连局域网厂商） |
| **M2**（11-26） | ① 本地编排 E2E：PIPELINE 与 SUPERVISOR 两模式实测（含 HUMAN 节点弹窗审批）；② 会话同步幂等：上行重放零副作用（client_id 去重验证）；③ 模型双源切换（本地 Key ↔ 平台代理）；④ 本地 Docker 沙箱资源限额验证 + 无 Docker 降级外显；⑤ 协同版本协商握手可用 |
| **M3**（12-23） | ① 分发链路演练：签名安装→自动更新→版本回退实测；② linux-arm64 信创包可用（毕昇 JDK + Electron arm64）；③ 私有化内网更新源 + 离线包交付清单；④ §13 NFR 全指标实测达标（体积/内存/启动）；⑤ 文档齐备（安装指南/用户手册/私有化部署附录/发布说明与引擎版本对齐） |

---

## 三、Phase 0 详细任务

> **周期**：W37-W38（2026-09-14 ~ 09-22），7 人日 | **目标**：用最小成本消除三大架构不确定性（引擎可嵌入性、JVM 体积、共享包可行性），并交付共享包。

### Spike-A：引擎单机启动 PoC（D0-1 ~ D0-4）

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D0-1 | 引擎独立启动验证 | 最小 Spring Boot 宿主 + gewu-agent-engine 依赖，验证 NoOp 默认装配可启动（`AgentEngineAutoConfiguration` 全 Bean 装载）；产出：启动日志与 Bean 清单 | 0.5d | V-3 |
| D0-2 | 最小 chat/stream 端点 | 宿主内暴露 `POST /ai/chat/stream`（透传引擎 ReactAgentExecutor 流式），配置本地厂商 Key 直连，SSE 事件流经 curl/脚本验证 13 种事件类型 | 0.5d | D0-1, P-3 |
| D0-3 | jlink 定制运行时实测 | jdeps 计算模块清单 → jlink（--compress）+ AppCDS；实测 JRE 体积与 Spring 启动耗时；产出：模块清单与数据 | 0.5d | D0-1 |
| D0-4 | **Go/No-Go 评审** | 汇总三项数据 vs 门禁（JRE ≤60MB / 启动 ≤3s）；不达标走降级路径（裁剪 micrometer 等可选依赖 / 完整 JRE 重测）；**触发 ADR-002B 复审条件** | 0.5d | D0-1~3 |

### Spike-B：协议 DTO 下沉评估（D0-5）

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D0-5 | DTO 下沉改动面评估 | 扫描 ChatStreamEvent/ChatRequest/AgentChunk 映射器的全部引用点；决策「移入 gewu-common」vs「新建 gewu-protocol 模块」；产出：评估结论（PT-E5 执行输入） | 0.5d | V-4 |

### 共享包抽离（D0-6 ~ D0-12）

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D0-6 | workspace 脚手架 | 根 pnpm-workspace.yaml；`packages/agent-ui` 包骨架（tsconfig / vite lib mode / 构建+测试脚本 / changeslet 版本策略） | 0.5d | — |
| D0-7 | protocol 包 | ChatStreamEvent 类型与事件常量、Result 信封、错误码枚举、IPC zod schema 草案（§5.3 通道表固化） | 0.5d | D0-6 |
| D0-8 | api-client 抽离 | 统一 HTTP 客户端（凭据注入可插拔：Bearer / X-Engine-Token）+ SSE 解析器自 `chat.ts` 抽离（保留残包 buffer/[DONE] 兼容行为） | 1d | D0-7 |
| D0-9 | process 包 | ProcessTracker / createProcessStreamHandler / toolHeadline 纯 TS 迁移 + vitest 单测 | 0.25d | D0-6 |
| D0-10 | chat-ui 抽离（第一刀拆分） | AIProcessTimeline 直迁；ChatPage 拆出 SessionList / Composer / MessageBubble / ModelPicker / AgentPicker 五个子组件（不追求完美抽象，行为等价优先） | 1d | D0-8, D0-9 |
| D0-11 | ui + theme 抽离 | MarkdownRenderer / FileCard / Toast / Select / themes + CSS 变量 | 0.5d | D0-6 |
| D0-12 | web 接入验证 | gewu-web chat 相关路径切至共享包；全量回归（222 前端测试基线 + 手工核心链路冒烟）；发布共享包 v0.1.0 | 0.75d | D0-7~11 |

**Phase 0 合计：7 人日**。并行项：P-1 证书采购启动、P-2 构建机就绪、V-1~V-4 核实项（W37 第一天）。

---

## 四、Phase 1 详细任务

> **周期**：W39-W44（2026-09-23 ~ 10-30，扣除国庆 23 人日） | **目标**：单机完整对话+工具闭环（G1 核心达成）。
> **平台侧硬前置**：PT-E1（D-2 修复）须于 W40 结束前完成，否则 D1-2 系列受阻（缓解见 §九 R1）。

### W39（09-23~09-30，6 人日）：壳层骨架

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D1-1 | Electron 工程初始化 | electron-vite 三目标（main/preload/renderer）；TypeScript 严格模式；ESLint/Prettier 对齐 zcode-rules | 0.5d | D0-12 |
| D1-2 | 主进程骨架 | 窗口/生命周期/单实例锁/托盘/菜单；渲染层 CSP 配置（connect-src 白名单） | 1d | D1-1 |
| D1-3 | EngineSupervisor MVP | spawn jlink JRE + jar；stdin 引导注入（port/token/secrets）；`/engine/health` 心跳 10s；退出码分类 + 自动重启 ≤3 次；`engine:status` IPC 推送 | 1d | D0-4 |
| D1-4 | SecretVault | safeStorage 封装（平台 token + 模型 Key）+ SM4 双层加密；`secret:*` IPC（渲染层永不接触明文） | 0.75d | D1-2 |
| D1-5 | preload + IPC 注册中心 | contextBridge 类型化 API；§5.3 全通道 handler 骨架；zod 载荷校验 | 0.75d | D1-2 |
| D1-6 | 渲染层 App Shell | 布局/工作区 Tab/desktopSlice（连接模式/引擎状态/工作区列表）/命令面板骨架 | 1d | D1-5 |
| D1-7 | 工作区管理 | 工作区选择器页（最近/新建/打开目录）+ AGENTS.md 检测标识 + `workspace:*` IPC | 1d | D1-6 |

### W40-W41（10-08~10-16，7 人日）：desktop-runtime MVP（平台前置 E1/E4/E5 须就绪）

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D1-8 | runtime 模块脚手架 | gewu-desktop-runtime Maven 模块；引擎装配（桌面 SPI Bean 覆盖 NoOp）；临时令牌过滤器（X-Engine-Token）；127.0.0.1 随机端口绑定 | 1d | PT-E4, D0-4 |
| D1-9 | H2 持久化 | Flyway schema（local_session/local_message/local_audit/local_usage/kv）；DesktopPersistenceService；WAL/文件位置（userData） | 1.5d | D1-8 |
| D1-10 | DesktopSessionContextService | 历史构建（默认 50 条可配，对齐引擎语义） | 0.5d | D1-9 |
| D1-11 | DesktopLocalLlmProvider | 本地模型配置管理（provider/model/baseURL）；Key 自 stdin 注入仅驻内存；引擎 OpenAiCompatibleClient 直连（E1 修复后的 toolCalls 序列化生效） | 1d | PT-E1, PT-E2 |
| D1-12 | Chat/Session/Models 端点 | Profile 子集三组端点 + ChatStreamEvent SSE 直写（协议 DTO 复用 PT-E5 下沉产物）+ 渲染层聊天页联调跑通第一句话 | 1d | D1-9~11 |
| D1-13 | Profile 契约测试基线 | 共享 fixture（消息序列→事件流）双端（本地 runtime / 平台 `/v3/api-docs` 对照）断言；CI 挂钩 | 1d | D1-12, PT-E5 |

### W42（10-19~10-23，5 人日）：本地工具集

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D1-14 | 工具脚手架 | @ToolProvider 注册验证（ToolRegistry 收集）；工具定义 JSON Schema 规范 | 0.5d | D1-8 |
| D1-15 | fs.* 工具族 | read/write/edit/list/glob/grep（java.nio）；PathGuard（realpath + 符号链接逃逸检测 + 工作区根约束）；fs.edit 唯一匹配语义（对齐 ZCode Edit） | 1.5d | D1-14 |
| D1-16 | shell.exec | ProcessBuilder（超时/工作目录/环境白名单/进程组清理）；CommandValidator（平台沙箱 16 条黑名单规则文件化共享） | 1d | D1-14 |
| D1-17 | git.* + project.context | git status/diff/log（CLI 封装）；AGENTS.md → rules/*.md → .gewu/skills/*.md 装载器（兼容本仓约定） | 1d | D1-14 |
| D1-18 | 输出管线收尾 | 输出截断 10KB / PII 脱敏正则 / local_audit 记录（含哈希链列） | 1d | D1-15~17 |

### W43（10-26~10-28，3 人日）：权限与授权流

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D1-19 | DesktopPermissionService | 三级权限模式（READ_ONLY/WORKSPACE_WRITE/FULL_AUTO）+ alwaysAllow 会话白名单（kv 表）+ 策略仲裁 | 1d | D1-8 |
| D1-20 | DesktopHitlGateway | requestApproval → SSE `approval_required` → 渲染层弹窗 → POST /approvals/{id} 恢复 Mono（30min 超时自动拒绝，对齐平台语义） | 1d | D1-19 |
| D1-21 | 权限 UI | PermissionDialog（工具/动作/target + diff 预览 + alwaysAllow，参考 git 6def6aa 蓝本重写）；审批中心 MVP（本地待办列表） | 1d | D1-20 |

> W43 剩余 2 人日并入 W44；引擎安全链本地生效验证（注入检测/SsrfValidator/CodeScanner 用例）并入 D1-18 与 M1 门禁测试。

### W44（10-29~10-30 + 前溢，4 人日）：桌面 UI 主链路集成

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D1-22 | 聊天工作台集成 | 共享包 ChatPage + ConnectionManager（单机模式 baseURL=127.0.0.1 + 临时令牌）；运行时指示器 | 1d | D1-12, D1-21 |
| D1-23 | 会话管理 UI | 本地会话列表/历史/搜索（接 Profile sessions 端点） | 0.75d | D1-22 |
| D1-24 | 文件树 + diff 查看 | FileTree（虚拟滚动 + git 状态标记）+ react-diff-viewer 只读 diff | 1.25d | D1-15 |
| D1-25 | 终端面板 | xterm.js + node-pty（多 Tab；「发送到终端」复现工具命令） | 1d | D1-2 |
| D1-26 | M1 集成验收 | 三平台 electron-builder 初配出包；E2E 冒烟清单执行；性能实测（门禁④⑤） | 1d | 全部 |

**Phase 1 合计：25 人日（W39 6 + W40-41 7 + W42 5 + W43 3 + W44 4）**。

---

## 五、Phase 2 详细任务

> **周期**：W45-W49（2026-11-02 ~ 11-26，19 人日） | **目标**：单机全能力（沙箱/MCP/编排/Agent 技能管理/记忆）+ 协同基础（模型共享/会话同步）。
> **平台侧硬前置**：PT-N1（LLM 代理）与 PT-N2/N3（同步端点 + D-3 幂等）须于 W47 前就绪。

### W45（11-02~11-06，5 人日）：沙箱与 MCP

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D2-1 | LocalDockerSandboxExecutor | docker CLI 封装（run --rm + CPU/内存/磁盘/网络限额对齐平台参数语义）；Docker 可用性检测；无 Docker 降级外显（code_execute 禁用标注） | 1.5d | D1-18 |
| D2-2 | 本地 MCP 管理 | DesktopMcpConfigSource（.gewu/mcp.json + H2）；MCP 管理页（本地 Tab）；StdioMcpClient 子进程池联调（空闲 10min 回收） | 1.5d | D1-8 |
| D2-3 | code_execute 接入 | 经 SandboxExecutor SPI 分派；CodeScannerCheck（引擎自带）用例验证 | 0.5d | D2-1 |
| D2-4 | MCP 工具授权 | MCP 工具粒度首用授权（PermissionService 扩展）+ 来源记录入审计 | 0.5d | D2-2, D1-19 |
| D2-5 | SandboxPage（本地） | 本地沙箱状态/资源占用展示 | 1d | D2-1 |

### W46（11-09~11-13，5 人日）：编排与 Agent/技能管理

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D2-6 | Orchestration 端点 | DesktopOrchestrationController：图 CRUD/execute/stream/goals（Profile 同构） | 1d | D1-12 |
| D2-7 | 编排工作台接本地 | OrchestrationPage + WorkflowCanvas 复用改造（ConnectionManager 数据源切换）；本地执行 SSE 时间线 | 1.5d | D2-6 |
| D2-8 | 本地 Agent 管理 | AgentManagePage/MyAgentsPage 改造接本地端点：CRUD/工具挂载/技能挂载/systemPrompt 编辑 | 1.25d | D1-12 |
| D2-9 | 本地技能库 | SkillLibraryPage 改造 + 工作区 .gewu/skills 扫描安装/卸载 | 1d | D2-8 |
| D2-10 | 记忆 v1 | DesktopMemoryStore（关键词/TF-IDF，H2）；MemoryRouter 注入验证 | 0.25d | D1-9 |

### W47-W48（11-16~11-20，5 人日）：协同基础·平台联调

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D2-11 | DesktopProxyLlmProvider | LlmProvider SPI 双源实现；平台 LLM 代理（PT-N1）联调：流式/工具调用/usage 回传/预算检查 | 1d | PT-N1, D1-11 |
| D2-12 | 会话上行同步 | DesktopSync 上行批量（client_id 幂等联调，依赖 PT-N3）；水位管理；重放零副作用验证 | 1d | PT-N2, PT-N3 |
| D2-13 | 会话下行与接续 | 增量拉取（updated_at 水位）；「接续为本地会话」（云端上下文注入本地引擎） | 1d | PT-N2 |
| D2-14 | 执行记录上报 | 本地执行记录复用 `/agents/executions` create/complete/fail 上送 | 0.5d | — |
| D2-15 | 资源清单合并 | 平台 Agent/技能/MCP 清单拉取 + ETag 缓存 + 与本地库合并视图 | 1.5d | D2-8, D2-9 |

### W49（11-23~11-26，4 人日）：协同基础·桌面侧收口

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D2-16 | ConnectionManager 平台模式 | 登录/自动刷新/X-Device-Id/设备命名；token 仅存 SecretVault | 1d | D2-15 |
| D2-17 | 连接向导 UI | gateway 地址 → 账号 → 设备命名；游客模式（单机）默认；版本协商握手（PT-N9）三态展示 | 1d | D2-16 |
| D2-18 | 本地用量仪表盘 | H2 聚合（token/成本/会话分布）+ UsagePage 复用 | 1d | D1-9 |
| D2-19 | 设置页完整化 | 连接/模型源（本地 Key 管理界面）/权限模式/同步策略/更新通道 | 0.5d | D2-17 |
| D2-20 | M2 集成验收 | 门禁①~⑤逐项实测；单机全能力回归 | 0.5d | 全部 |

**Phase 2 合计：19 人日**。

---

## 六、Phase 3 详细任务

> **周期**：W50-W53（2026-11-27 ~ 12-23，19 人日） | **目标**：协同完整化 + 分发链路 + 信创 arm64（G3/G5 达标）。
> **平台侧硬前置**：PT-N4（SSE 广播 Redis 化）须于 W50 前就绪。

### W50（11-27 + 11-30~12-04，6 人日）：协作会话与远程审批

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D3-1 | 协作会话参与 | 订阅 `GET /sse/sessions/{id}`（message/approval_required 命名事件）；桌面发消息；会话列表云端 Tab | 1.5d | PT-N4, D2-16 |
| D3-2 | 统一审批中心 | 三类待办收口：本地工具授权历史 / 本地编排 HITL / 远程平台审批（`/approvals` 批准驳回） | 1.5d | D3-1, D1-21 |
| D3-3 | 云端执行路由 | 用户显式选择「云端执行」→ 经网关调 `/ai/chat/stream`（渲染层透明，ConnectionManager 分流） | 1d | D2-16 |
| D3-4 | 云端沙箱回退 | 本地 Docker 缺失 → `/sandboxes` 云端执行；SandboxPage 云端 Tab | 1d | D2-5 |
| D3-5 | Agent 市场页 | AgentMarketPage 接平台（浏览/详情/安装引导）；单机模式入口隐藏 | 1d | D2-15 |

### W51（12-07~12-11，4 人日）：平台资源导入

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D3-6 | 平台 Agent 导入本地 | 导入向导：Agent 配置 + 工具本地可用性逐项检测标注（http 可直连/mcp 需本地化/code_execute 依赖沙箱） | 1.5d | D2-15 |
| D3-7 | 平台技能安装 | 安装到本地技能库/工作区 .gewu/skills/ | 0.75d | D2-9 |
| D3-8 | 平台 MCP 本地化 | stdio 类型配置一键本地拉起；sse/streamable_http 走云端路由标注 | 1.25d | D2-4 |
| D3-9 | Phase 3 中期回归 | 协同场景冒烟（登录→拉取→导入→本地执行→上送） | 0.5d | D3-6~8 |

### W52（12-14~12-18，5 人日）：分发工程

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D3-10 | 打包配置完善 | electron-builder 三平台正式配置（图标/安装文案/卸载清理/自更新元数据）；jlink JRE + jar 打入 resources | 1d | P-2 |
| D3-11 | 签名链路 | Windows Authenticode / macOS 公证（notarytool）/ Linux 包签名；引擎 jar sha512 清单签名；EngineSupervisor spawn 前校验 | 1d | P-1 |
| D3-12 | 自动更新接入 | electron-updater 双通道（stable/beta）；L2 引擎 jar 独立替换通道（签名校验+失败回退）；强制升级引导（协商不兼容时锁定协同） | 1d | D3-10 |
| D3-13 | Jenkins desktop stage | 流水线：pnpm build/test → mvn runtime package → electron-builder → 签名 → MinIO 上传 + latest.yml；路径过滤触发 | 1.5d | D3-10 |
| D3-14 | 更新演练 | 签名安装→旧版→自动更新→版本回退全链路实测（M3 门禁①） | 0.5d | D3-11~12 |

### W53（12-21~12-23，4 人日）：信创与发布

| 任务ID | 任务 | 内容与产出 | 估时 | 依赖 |
|--------|------|-----------|------|------|
| D3-15 | linux-arm64 构建 | 毕昇 JDK 21 jlink + Electron arm64 出包实测（麒麟/UOS 兼容性冒烟清单） | 1.5d | P-2, D3-10 |
| D3-16 | 私有化交付包 | 内网更新源配置说明 + 全量离线安装包 + 历史版本留存清单 | 0.5d | D3-13 |
| D3-17 | 文档 | 安装指南 / 用户手册（单机+协同双模式）/ 私有化部署附录 / 发布说明（与引擎版本对齐，标注引擎简化项） | 1.5d | — |
| D3-18 | M3 验收与发布 | NFR 全指标实测（§13）；全量回归；正式版 tag + 发布 | 0.5d | 全部 |

**Phase 3 合计：19 人日**。

---

## 七、平台侧配套任务清单

> 归属平台迭代人力池；窗口为「须完成的最后期限」，越早完成越少阻塞。每项含设计文档 §10.2/10.3 编号对应关系。

| 任务ID | 内容 | 工作量估算 | 完成期限 | 阻塞的桌面任务 |
|--------|------|-----------|----------|----------------|
| PT-V2 | D-2 修复状态核实（可能已完成） | 0.25d | W37 | PT-E1 |
| PT-E1 | 引擎 D-2：Message toolCalls 序列化 + LlmRequestBodyBuilder + 三厂商回归 | 1.5d | **W40 末（10-16）** | D1-11, D1-12 |
| PT-E2 | 引擎 D-10：requestTimeout/预算/历史条数配置化（AgentEngineProperties） | 0.5d | W40 末 | D1-11 |
| PT-E4 | 引擎产物发布：Maven 坐标 + 仓库开通 + 版本发布流程 | 0.5d | W39 末（09-30） | D1-8 |
| PT-E5 | 协议 DTO 下沉：ChatStreamEvent/ChatRequest/映射器 → gewu-common（按 D0-5 结论） | 1d | W41 末（10-16） | D1-12, D1-13 |
| PT-N1 | LLM 代理端点：`/ai/llm-proxy/chat/stream`（SSE 归一化 chunk + 预算 + usage 记账） | 2d | **W47 末（11-20）** | D2-11 |
| PT-N3 | D-3：seq 原子化 + client_id 幂等查重 | 1.5d | W47 末 | D2-12 |
| PT-N2 | DesktopSyncController：sessions 上行/下行 + usage 批量上报 | 2d | W47 末 | D2-12, D2-13 |
| PT-N9 | `/platform/info` 版本协商端点 | 0.5d | W49 末（11-26） | D2-17 |
| PT-N4 | SseEventManager → Redis Pub/Sub（与平台多副本改造合并实施） | 3d | **W50 前（11-27）** | D3-1, D3-2 |
| PT-N6 | 资源 manifest 聚合端点（可选，先用列表端点代替） | 1d | 可选 | D2-15（可绕过） |
| PT-N8 | 审计上送端点（P4） | 1d | Phase 4 | — |

**平台侧合计：约 15 人日（含可选 ~3 人日）**。

---

## 八、依赖关系与关键路径

### 8.1 依赖拓扑

```
P-1/P-2/P-3/P-4(前置条件) ──────────────────────────────────────────┐
                                                                    ▼
V-1~V-4 核实 ─► D0-1~D0-4 Spike-A ─► M0 门禁 ─┬─► D1-1~D1-7 壳层 ──┐
               D0-5 Spike-B ─► PT-E5          │                     ├─► M1
               D0-6~D0-12 共享包 ─────────────┘   └─► D1-8~D1-13 runtime（◄ PT-E1/E2/E4/E5）
                                                                        │
                                            D1-14~D1-18 工具 ──┐        │
                                            D1-19~D1-21 权限 ──┼───────┘
                                                               ▼
                              D1-22~D1-26 UI 主链路 ═══ M1 ═══
                                                               ▼
              D2-1~D2-5 沙箱/MCP ─┐
              D2-6~D2-10 编排/管理 ├─► D2-11~D2-15 协同基础（◄ PT-N1/N2/N3）─► M2
                                                               ▼
              D3-1~D3-5 协作/审批（◄ PT-N4）─► D3-6~D3-9 资源导入
                                                               ▼
              D3-10~D3-14 分发（◄ P-1 证书）─► D3-15~D3-18 信创/发布 ═══ M3
```

### 8.2 关键路径（情景 A）

**P-1 证书采购 → D3-11 签名 → D3-14 更新演练 → M3**（最长提前期链，采购风险独立于开发进度）；
开发关键路径：**D0-4 Go → D1-8 runtime（卡 PT-E1）→ D1-12 联调 → D1-22 集成 → M1 → D2-12 同步（卡 PT-N3）→ M2 → D3-1（卡 PT-N4）→ M3**。
平台侧三项硬期限（PT-E1 @W40、PT-N1/N2/N3 @W47、PT-N4 @W50）是关键路径上的外部节点，**逾期即顺延对应里程碑**（1:1 传导）。

### 8.3 并行度设计（单人节奏）

每个工作周内部保持「Java（runtime）与 TS（壳/UI）任务交错」：如 W39 壳层为主，W40-W41 runtime 为主，避免单一技术栈连续疲劳；UI 联调任务（D1-22~25、D2-7、D3-1）安排在对应后端任务完成当周尾，缩短反馈回路。

---

## 九、风险登记册

> 承接设计文档 §十四 R1~R9，落到阶段触发器与缓解动作。周检时更新状态。

| ID | 风险 | 触发器（监测信号） | 缓解动作 | 兜底路径 |
|----|------|--------------------|----------|----------|
| RK-1 | PT-E1（D-2）逾期 → Phase 1 runtime 受阻 | W40 末未合入 | W37 即核实状态（PT-V2）；若逾期，D1-11 先用本地 mock provider（固定响应 fixture）推进 UI/壳层线，runtime 联调后置 | UI 线不停，D1-12 顺延 ≤1 周 |
| RK-2 | 引擎测试覆盖不足（R2） | M1 门禁安全用例失败率 >0 | 每阶段适配层单测随任务交付（D1-13/D2-20）；引擎核心测试与平台 sprint 合流推进 | M1 后专项补测窗口（W45 预留） |
| RK-3 | jlink 体积/启动超标（R3） | D0-3 实测 JRE >60MB 或启动 >3s | D0-4 评审走降级路径：裁剪可选依赖（micrometer）→ 完整 JRE 重测 → 预算上调评审（ADR 变更） | 若仍不达标，回到 ADR-002B 复审 |
| RK-4 | Profile 双端漂移（R4） | D1-13 契约测试失败 | PT-E5 下沉为强制前置；契约测试进 CI 门禁 | 冻结协议变更至 E5 完成 |
| RK-5 | 单人带宽（R5） | 任意周实际完成 < 计划 80% | 周检时 scope 砍序：M1 范围不可砍 → M2 中「记忆 v1/用量仪表盘」可后移 → M3 中「信创 arm64」可后移至 Phase 4 | 情景 B 排期规则（§1.2） |
| RK-6 | LLM 代理平台负载（R6） | D2-11 联调限流触发频繁 | 代理端点独立预算上限 + 压测（50 并发 SSE）；错峰联调 | 桌面端默认回落本地 Key 模式 |
| RK-7 | Electron/Chromium CVE（R7） | 安全通告 | 季度版本评估 + Dependabot，维护期执行 | — |
| RK-8 | 信创 arm64 兼容（R8） | D3-15 冒烟失败 | 毕昇 JDK + Electron 官方 arm64 组合先行；问题分层定位（JVM/Chromium/原生模块——H2 纯 Java 已消除一类） | arm64 后移 Phase 4，loongarch 持续观察 |
| RK-9 | 引擎简化项被感知为桌面缺陷（R9） | 内测反馈 | 发布说明与引擎版本对齐 + 能力边界文档（§9.2） | — |
| RK-10 | 签名证书采购逾期（新增） | W45 前未到位 | W37 启动采购（P-1）；公证/签名流程在 CI 中 dry-run（自签过渡） | M3 分支：无签名版限内测渠道，正式发布顺延 |

---

## 十、质量门禁与测试策略

### 10.1 分层测试策略

| 层 | 工具 | 覆盖要求 | 挂钩阶段 |
|----|------|----------|----------|
| desktop-runtime（Java） | JUnit 5 + MockWebServer（LLM 厂商模拟） | 适配层行覆盖 ≥70%；PathGuard/CommandValidator/PermissionService 必须全分支 | D1-8 起持续 |
| 引擎契约 | 共享 fixture 回放（消息序列→事件流骨架断言） | Profile 双端一致；每次协议 DTO 变更必跑 | D1-13 起 CI 门禁 |
| 共享包（TS） | vitest | protocol/api-client/process 单测；chat-ui 关键交互 | D0-7 起 |
| IPC/壳层 | zod schema 测试 + 手工矩阵 | 通道载荷校验；崩溃恢复场景 | D1-5 起 |
| E2E | 手工冒烟清单（每里程碑更新）+ M3 起评估 Playwright-Electron 自动化 | 门禁场景逐条执行 | M1/M2/M3 |
| 性能 | 脚本实测（启动/首 token/内存） | §13 NFR 指标 | M1 起每里程碑复测 |

### 10.2 安全检查清单（M1/M3 各执行一次）

路径逃逸（符号链接用例）× 提示注入（8 高危正则用例）× 命令黑名单（16 条用例）× 密钥零明文（内存/磁盘扫描）× 临时令牌（本机未授权访问用例）× 更新签名（篡改包拒绝用例）× MCP 供应链（未确认命令行拒绝）。

### 10.3 缺陷管理约定

Bug 分级 P0（崩溃/数据丢失/安全）阻断里程碑 / P1（核心功能）里程碑前必须清零 / P2（体验）可带入下一阶段但每次周检复核。完成记录归档至 `docs/plan/done/`（沿用项目惯例：`gewu-desktop-phase1-done.md` 等）。

---

## 十一、进度跟踪机制

| 机制 | 频率 | 内容 |
|------|------|------|
| 周计划/周报 | 每周五 | 本周任务状态（✅/🔄/⏸）、下周计划、风险登记册更新、阻塞项（含平台侧 PT 逾期预警） |
| 里程碑评审 | M0~M3 各一次 | 门禁逐项核验 → Go/No-Go；scope 调整决策；计划基线变更记录（版本号递增） |
| 平台侧对齐 | 双周 | PT 系列进度对齐（关键期限 W40/W47/W50 各加一次专项确认） |
| 状态标记 | — | ⏳ 未开始 / 🔄 进行中 / ✅ 完成 / ⛔ 阻塞 / ➡️ 移出范围（归档原因） |

**计划变更纪律**：里程碑日期、scope 增减、人力情景切换均须在本文档修订记录留痕并同步更新依赖拓扑，禁止口头变更。

---

## 十二、Phase 4 Backlog（远期，不占用本计划工期）

| 条目 | 前置 | 备注 |
|------|------|------|
| 记忆 v2：本地 ONNX 嵌入（bge-small-zh 按需下载） | M3 | 体积 +90MB 走可选下载 |
| 审计上送平台哈希链（PT-N8） | 平台端点 | 等保增强 |
| 本地 Agent 发布平台市场 | 无 | 反哺生态 |
| 企业策略包下发（PolicyService 接收） | 平台端点 | 企业管控 |
| 多端会话移交（gewu:// 深链唤起） | 无 | 与 Web 端联合设计 |
| 引擎 jar 独立更新通道常态化 | D3-12 | L2 通道运营化 |
| Monaco 只读代码浏览 | 无 | 替换 react-diff-viewer |
| Playwright-Electron E2E 自动化 | M3 | 视维护带宽 |
| loongarch 信创包 | 龙芯 OpenJDK 21 + Electron loong64 生态成熟 | 观察项 R8 |

---

## 修订记录

| 版本 | 日期 | 修订内容 |
|------|------|----------|
| V1.0 | 2026-09-09 | 初版：基于架构设计 V2.2 制定 Phase 0~3 全量 WBS（68 桌面人日 + 15 平台人日）、四里程碑门禁、依赖拓扑与关键路径、风险登记册、测试策略与跟踪机制 |

---

*执行入口：W37（2026-09-14）启动日检查单——① V-1~V-4 核实项 ② P-1 证书采购下单 ③ P-2/P-3/P-4 环境就绪 ④ 本计划基线确认*
