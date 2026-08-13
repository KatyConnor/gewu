# 认知型 Agent 架构实施计划

> **文档编号**：PLAN-CAA-GEWU-2026-08
> **版本**：V2.0（已实施更新）
> **发布日期**：2026年8月13日
> **更新日期**：2026年8月14日
> **文档类型**：架构实施计划
> **目标架构**：《认知型 Agent 智能体架构设计技术文档（综合实施版）》DOC-CAA-MASTER-2026-08
> **适用项目**：gewu-platform（格物智能体平台）
> **实施状态**：阶段一至五已完成核心实施，全项目编译通过

---

## 第一部分：现状评估

### 1.1 项目概况

gewu-platform 是一个基于 **Java 21 + Spring Boot 3.2** 的 DDD 模块化单体架构智能体平台，包含 8 个 Maven 模块：

| 模块 | 职责 | 代码量 |
|------|------|--------|
| gewu-common | 跨切面基础（国密加密、JWT、ULID、结果封装、注解） | ~40 类 |
| gewu-domain | 领域实体（agent/session/workflow/wenshi/project 等） | ~60 实体 |
| gewu-infrastructure | 基础设施（MyBatis Mapper、LLM 客户端、MCP、缓存、存储） | ~80 类 |
| gewu-application | 应用服务（Agent 执行、Wenshi 认知、工作流、会话、权限等） | ~120 类 |
| gewu-interface | 接口层（27 个 Controller、安全过滤器、SSE） | ~30 类 |
| gewu-gateway | API 网关（JWT 认证、限流、熔断） | ~10 类 |
| gewu-sandbox | 沙箱执行（Docker 隔离、命令校验、审计） | ~25 类 |
| gewu-agent-engine | Agent 编排引擎框架（独立可复用） | ~100 类 |

**前端**：gewu-web（Next.js + TypeScript），包含聊天、Agent 管理、工作流画布、审计中心等页面。

### 1.2 已实现的核心能力

基于 codebase-memory 知识图谱分析（16,456 节点 / 38,004 边），当前已实现：

| 能力域 | 实现状态 | 关键类/模块 |
|--------|---------|------------|
| **Agent Loop（ReAct）** | ✅ 完整实现 | `ReactAgentExecutor`（同步+流式）、`AgentEngine` 门面、`AgentTask` |
| **工具执行** | ✅ 完整实现 | `ToolExecutor`、`ToolRegistry`、MCP 集成（stdio+SSE）、安全链 |
| **LLM 多供应商** | ✅ 完整实现 | `LlmClientRegistry`、DeepSeek/Qwen/OpenAI 兼容客户端 |
| **编排引擎框架** | ✅ 框架就绪 | `OrchestrationEngine`、4 种模式（Pipeline/Supervisor/Swarm/Debate） |
| **编排运行时** | ✅ 框架就绪 | `ReactRuntime`、`PlanExecuteRuntime`、`ReflexionRuntime` |
| **角色注册表** | ✅ 完整实现 | `RoleRegistry`（12 个 SDLC 内置角色）、`AgentRoleSpec` |
| **Wenshi 认知子系统** | ✅ 完整实现 | `Planner`、`Critic`、`SolverRouter`、`MultiAgentCoordinator` |
| **Wenshi 记忆系统** | ✅ 完整实现 | 5 类记忆（语义/情景/程序/参数/经验）+ pgvector + HNSW |
| **Wenshi 学习进化** | ✅ 完整实现 | `ExperienceExtractor`、`SkillEvolver`、`ReflectionAgent` |
| **安全网关** | ✅ 最完整领域 | `SecurityChain`、权限校验、SSRF 防护、代码扫描、审计 |
| **沙箱执行** | ✅ 基本完整 | Docker 隔离、命令校验、资源限制、SM3 审计链 |
| **工作流引擎** | ✅ 独立实现 | 9 张表、状态机、并行分支、超时检测、幂等 |
| **会话管理** | ✅ 完整实现 | 事件溯源会话、上下文压缩、SSE 流式推送 |
| **RBAC 权限** | ✅ 完整实现 | 五维 RBAC、数据权限、国密认证 |
| **SPI 桥接** | ✅ 10 个适配器 | DB 驱动的 SPI 适配器连接 agent-engine 与平台 |

### 1.3 关键架构问题

通过代码图谱分析，识别出 **5 个核心架构问题**：

#### 问题1：Wenshi 认知能力与 Agent 引擎 SPI 割裂 ⚠️ 严重

```
gewu-agent-engine/cognition/     gewu-application/wenshi/reasoning/
┌──────────────────────┐         ┌──────────────────────┐
│ ReasoningKernel (SPI)│         │ Planner (真实实现)    │
│  plan() → NoOp       │  ✗未桥接 │  plan() → PlanTree   │
│  critique() → NoOp   │         │ Critic (真实实现)    │
└──────────────────────┘         │  evaluate() → Result │
                                 └──────────────────────┘

gewu-agent-engine/memory/        gewu-application/wenshi/knowledge/
┌──────────────────────┐         ┌──────────────────────┐
│ MemoryStore (SPI)    │         │ SemanticMemoryService│
│  store() → NoOp      │  ✗未桥接 │ EpisodicMemoryService│
│  retrieve() → NoOp   │         │ ProceduralMemorySvc  │
└──────────────────────┘         │ MemoryRouter (真实)  │
                                 └──────────────────────┘
```

**影响**：Agent 引擎运行时，认知和记忆 SPI 全部为 NoOp 空实现，真实能力无法生效。这是当前最大的架构债务。

#### 问题2：编排引擎未持久化、未暴露 API ⚠️ 严重

`OrchestrationEngine` 的 `pause()`/`resume()`/`cancel()` 为空桩方法；编排图定义表、执行实例表、审批请求表等 **均未在数据库迁移中创建**；`gewu-interface` 中无 `OrchestrationController`。编排引擎当前仅能内存运行，无法持久化、无法通过 API 调用。

#### 问题3：工作流引擎与 Agent 编排引擎双轨并行 ⚠️ 中等

存在两套独立的编排系统：
- **工作流引擎**（`gewu-application/workflow`）：DB 驱动、状态机、9 张表、有 Controller
- **Agent 编排引擎**（`gewu-agent-engine/orchestration`）：内存运行、图模型、4 种模式、无持久化

两者概念重叠但实现独立，未按架构文档"编排即图"理念统一。

#### 问题4：预算控制未实施 ⚠️ 中等

`AutonomousGoal.budgetTokens` 仅为字段，**未与实际 Token 消耗挂钩**；无 `BudgetController` 类；无成本追踪；无降级熔断机制。`maxIterations`（默认 5）和 `maxToolRounds`（默认 10）是仅有的硬限制。

#### 问题5：HITL 仅为 SPI 桩 ⚠️ 中等

`HitlGateway` SPI 仅有 `NoOpHitlGateway`（自动批准），无真实实现。审批请求表未创建。前端审计中心页面已存在但无后端审批 API 支撑。

---

## 第二部分：差距分析

### 2.1 架构文档能力清单 vs 当前实现

将架构文档（DOC-CAA-MASTER-2026-08）的能力清单与 codebase-memory 分析结果逐项对照：

| # | 架构文档能力 | 当前实现状态 | 差距说明 |
|---|------------|------------|---------|
| **分层架构** | | | |
| 1 | 五层分层 + 治理横切面 | ✅ 基本符合 | DDD 四层（interface/application/domain/infrastructure）+ common，治理通过注解+AOP 横切。缺少独立的治理横切面抽象 |
| **核心抽象** | | | |
| 2 | 三元组（节点-边-状态） | ✅ 已实现 | `OrchestrationGraph`/`GraphNode`/`GraphEdge`/`OrchestrationContext` |
| 3 | 6+2 类节点类型 | ✅ 已实现 | AGENT/TOOL/HUMAN/ROUTER/PARALLEL/MERGE/SUBGRAPH |
| 4 | 五种控制原语 | ⚠️ 部分 | Branch/Parallel/Merge 已实现；Retry 有基本重试但无质量门控重试；HITL 节点存在但无真实审批流程 |
| 5 | Agent Loop 引擎 | ✅ 已实现 | `ReactAgentExecutor` + 四个关键工程细节（Schema 验证、上下文管理、错误处理、多维终止） |
| **认知引擎** | | | |
| 6 | 感知引擎（Perception） | ❌ 缺失 | 无独立感知引擎，输入处理由 Gateway 过滤器承担 |
| 7 | 规划引擎（Planner） | ⚠️ 割裂 | agent-engine `ReasoningKernel.plan()` 为 NoOp；wenshi `Planner` 真实实现但未桥接 |
| 8 | 执行引擎（Executor） | ✅ 已实现 | `ReactAgentExecutor` + `ToolExecutor` + 沙箱 |
| 9 | 验证引擎（Verifier） | ⚠️ 割裂 | `ReasoningKernel.critique()` 为 NoOp；wenshi `Critic` 真实实现但未桥接 |
| 10 | 仲裁引擎（Arbiter） | ❌ 缺失 | 仅 Debate 模式有 judge 概念；无独立仲裁引擎/分级仲裁机制 |
| 11 | 记忆引擎（Memory） | ⚠️ 割裂 | agent-engine `MemoryStore` 为 NoOp；wenshi 5 类记忆真实实现但未桥接 |
| 12 | 可配置认知管线（L1/L2/L3 裁剪） | ❌ 缺失 | 无任务等级路由器、无动态引擎裁剪 |
| 13 | 复杂度路由器 | ❌ 缺失 | wenshi `SolverRouter` 是子目标策略路由，非任务复杂度路由 |
| 14 | 双系统思考（System 1/2） | ❌ 缺失 | 无双系统切换机制 |
| 15 | 双闭环验证（收敛版） | ❌ 缺失 | 无内环（≤5 轮）+ 外环（≤2 轮）验证机制 |
| 16 | 置信度门控 | ❌ 缺失 | 无 `ConfidenceGate` 类、无按需触发的置信度评估 |
| **记忆体系** | | | |
| 17 | 渐进式五层记忆 | ⚠️ 部分实现 | wenshi 有 5 类记忆但分层不明确；L1 工作记忆=Redis 未确认；L2 短期记忆缺失 |
| 18 | 经验蒸馏管道 | ⚠️ 部分实现 | `ExperienceExtractor`+`QualityAssessor` 已实现提取+评估；但无离线 T+1 批处理调度、无人工审核入库流程 |
| 19 | 反思机制 | ⚠️ 部分实现 | `ReflectionAgent` 已实现但独立运行，未嵌入编排生命周期钩子 |
| 20 | 失败案例库 | ❌ 缺失 | 无 `failure_case` 表、无结构化失败案例管理 |
| 21 | 技能进化机制 | ✅ 已实现 | `SkillEvolver`（经验≥3 次 + 评分≥0.7 → 固化技能） |
| 22 | 认知调度器 | ❌ 缺失 | 无定时监控+识别改进机会+优先级排序+触发自学习 |
| **编排引擎** | | | |
| 23 | 图编排引擎（万物皆节点） | ✅ 已实现 | `OrchestrationEngine` + `Orchestrator` |
| 24 | 不可变状态管理 | ⚠️ 部分 | `StateManager` 设计存在但实际状态管理在 `OrchestrationContext` 中，非严格不可变 |
| 25 | 双图解耦（计划图/执行图） | ❌ 缺失 | 当前仅有单一执行图 |
| 26 | 制品契约 | ❌ 缺失 | 无 `ArtifactContract` 接口、无 Schema 强制校验 |
| 27 | 进化钩子 | ⚠️ 部分 | `EvolutionHook` SPI 已定义（NoOp）；wenshi 有收集/提取/记录能力但未嵌入钩子 |
| **多 Agent 协作** | | | |
| 28 | Agent 角色模型 | ✅ 已实现 | `AgentRoleSpec` + `CapabilityCard` + `RoleRegistry`（12 角色） |
| 29 | Agent 通信协议 | ⚠️ 部分 | 无标准化 `AgentMessage` 信封；消息通过编排器隐式传递 |
| 30 | 四种协作模式 | ✅ 已实现 | Pipeline/Supervisor(委托-回报)/Swarm(审查-迭代)/Debate(辩论) |
| 31 | 冲突解决（四层优先级） | ❌ 缺失 | 仅 Debate judge 模式；无权威优先/多数表决/LLM 仲裁/人工裁决分层 |
| 32 | Agent 生命周期管理 | ⚠️ 部分 | 无死锁检测、无心跳监控、无超时自动终止 |
| 33 | 协作预算预检 | ❌ 缺失 | 无协作前 Token 预算评估 |
| 34 | 自主循环防失控（六重边界） | ⚠️ 部分 | 仅 `maxIterations`+`maxToolRounds`；无时间预算/HITL 强制/回滚限制/资源配额 |
| **治理与质量** | | | |
| 35 | 四环协同（Execute/Evaluate/Govern/Audit） | ❌ 缺失 | 仅有运算环（Execute）+审计（AuditService）；无评估环、无治理环 |
| 36 | LLM-as-Judge + SPC | ❌ 缺失 | 无锚点用例集、无 LLM 评估器、无 SPC 退化检测 |
| 37 | 共享知识层 | ⚠️ 部分 | Trace/Metric/Policy API 部分通过审计服务实现，未标准化 |
| 38 | 可观测性三层 | ⚠️ 部分 | L1 系统层（Prometheus+Grafana 部署配置已有）；L2 应用层缺 OpenTelemetry；L3 Agent 层缺 Langfuse |
| **安全与成本** | | | |
| 39 | 安全纵深防御（五层） | ✅ 大部分实现 | 身份认证✓、工具安全✓、沙箱✓、审计✓；提示注入防护⚠️(仅规则)、输出安全⚠️(PII 检测缺失) |
| 40 | 渐进式权限模型（L0-L3） | ⚠️ 部分 | RBAC 已实现但非基于成功率渐进释放；`PermissionService` 存在但无信任等级累积 |
| 41 | 模型路由（预算→隐私→复杂度→延迟→最便宜） | ❌ 缺失 | `LlmClientRegistry` 仅按供应商路由，无成本/复杂度感知 |
| 42 | 预算门控（四维预算） | ❌ 缺失 | 仅 `maxIterations`/`maxToolRounds` 硬限制；无 Token/时间/成本四维预算 |
| 43 | 语义缓存 | ❌ 缺失 | 仅有 `@CacheResult` 注解做方法级缓存，无向量语义缓存 |
| **接口与部署** | | | |
| 44 | 工具协议（MCP 兼容） | ✅ 已实现 | `ToolDescriptor` + MCP 客户端完整 |
| 45 | 流式事件协议 | ⚠️ 部分 | `AgentEvent` 已定义但类型不全（缺 budget_warning/reflection/experience_saved） |
| 46 | K8s 部署拓扑 | ✅ 部署配置已有 | `deploy/k8s/` 有 deployment/service/hpa/ingress 等配置 |
| 47 | LLM 网关统一入口 | ⚠️ 部分 | `LlmClientRegistry` 统一入口存在，但无限流/缓存/成本追踪 |

### 2.2 差距统计

| 状态 | 数量 | 占比 | 说明 |
|------|------|------|------|
| ✅ 已实现 | 15 | 32% | 可直接复用 |
| ⚠️ 部分实现/割裂 | 14 | 30% | 需增强或桥接 |
| ❌ 缺失 | 18 | 38% | 需新建 |
| **合计** | **47** | 100% | |

### 2.3 成熟度定位

对照架构文档的 L0-L3 成熟度模型：

| 成熟度 | 能力特征 | 当前状态 |
|--------|---------|---------|
| L0 单Agent | 感知→执行、2引擎、无协作 | ✅ 已超越（有规划+编排框架） |
| L1 规划Agent | 感知→规划→执行→验证、4引擎、基础策略 | ⚠️ **当前定位**（框架就绪但验证/预算/记忆未生效） |
| L2 协作Agent | 六引擎完整、双闭环、四模式、安全网关 | 🔜 下一阶段目标 |
| L3 自进化Agent | 经验蒸馏、认知调度、联邦协作 | 远期目标 |

**结论**：项目当前处于 **L0→L1 过渡期**——Agent Loop 基础设施已就绪，编排框架已搭建，但认知引擎 SPI 未桥接、验证/预算/记忆等 L1 核心能力未生效。当务之急是**打通割裂**，让已实现的能力真正运行起来。

---

## 第三部分：实施路线图

### 3.1 总体策略：先连通再增强，先工程再认知

```
当前状态                    目标状态
┌──────────┐              ┌──────────────────────┐
│ L0→L1    │              │ L2 协作Agent          │
│ 框架就绪  │  ═══════►   │ 六引擎完整·双闭环     │
│ SPI 空转  │   5 阶段     │ 四模式·预算驱动       │
│ 双轨并行  │   12 个月    │ 治理内嵌·成本可控     │
└──────────┘              └──────────────────────┘
```

**核心原则**：
1. **先连通再增强**：优先桥接 Wenshi 认知/记忆到 agent-engine SPI，让已有能力生效
2. **先工程再认知**：先解决持久化/API/预算等工程基础设施，再叠加认知层
3. **先单 Agent 再多 Agent**：先让单 Agent 认知闭环跑通，再增强多 Agent 协作
4. **每阶段可交付**：每阶段产出可验证的功能增量，非一次性大爆炸

### 3.2 五阶段路线图

```
阶段一: SPI 桥接与编排落地 (0-2月) → L1 规划Agent
├─ 目标: 让已有认知/记忆能力生效，编排引擎可持久化可调用
├─ 核心交付: Wenshi→SPI 桥接 · 编排 DB 表 · Orchestration API
├─ 里程碑: 编排引擎通过 API 可执行可追踪
├─ 准出: 单 Agent 认知闭环（规划→执行→验证→记忆）端到端跑通
│
阶段二: 认知增强与验证闭环 (2-4月) → L1+ 认知Agent
├─ 目标: 双闭环验证 + 置信度门控 + 预算控制上线
├─ 核心交付: 双闭环机制 · 置信度门控 · BudgetController · 失败案例库
├─ 里程碑: 验证一次通过率≥85% · 预算熔断可生效
├─ 准出: 任务成功率≥85% · 预算超限自动降级
│
阶段三: 协作增强与治理建设 (4-7月) → L2 协作Agent
├─ 目标: 多 Agent 协作完善 + HITL + 四环治理
├─ 核心交付: HITL 全流程 · 冲突解决 · 四环 Phase1-2 · LLM-as-Judge
├─ 里程碑: 3+Agent 协作场景跑通 · 缺陷检出率≥60%
├─ 准出: HITL 响应时间<30min · 契约校验通过率≥95%
│
阶段四: 成本优化与自进化 (7-10月) → L2+ 增强Agent
├─ 目标: 成本治理 + 经验蒸馏 + 认知调度
├─ 核心交付: 模型路由 · 语义缓存 · 经验蒸馏管道 · 认知调度器
├─ 里程碑: 成本降低≥30% · 经验复用率≥30%
├─ 准出: System 1 分流率≥60% · 知识 T+1 入库可验证
│
阶段五: 自我进化与联邦 (10月+) → L3 自进化Agent
├─ 目标: 完整自学习 + WORM 审计 + 多场景适配
├─ 核心交付: 在线学习探索 · WORM 审计 · 红蓝对抗 · 联邦协作
└─ 准出: 任务成功率≥90% · HITL 率≤10% · 新场景接入<2周
```

---

## 第四部分：各阶段详细任务

### 阶段一：SPI 桥接与编排落地（0-2 月）

> **目标**：消除 Wenshi 与 agent-engine 的割裂，让编排引擎可持久化、可通过 API 调用。这是所有后续工作的基础。

#### 任务 1.1：Wenshi 认知 SPI 桥接 ⭐ 最高优先级

**问题**：`ReasoningKernel`（plan/critique）为 NoOp，wenshi `Planner`/`Critic` 真实实现未接入。

**实施方案**：

```
gewu-application/agent/adapter/
├── WenshiReasoningKernelAdapter.java   ← 新建，实现 ReasoningKernel
│   ├── plan() → 委托 wenshi Planner.plan()
│   ├── critique() → 委托 wenshi Critic.evaluate()
│   └── routeSolver() → 委托 wenshi SolverRouter.selectStrategy()
├── WenshiReflectionEngineAdapter.java  ← 新建，实现 ReflectionEngine
│   ├── reflect() → 委托 wenshi ReflectionAgent.reflect()
│   └── replan() → 委托 wenshi Planner.replan()
└── WenshiEvolutionHookAdapter.java     ← 新建，实现 EvolutionHook
    ├── onNodeComplete() → 委托 ExperienceCollector
    ├── onGraphComplete() → 委托 ExperienceExtractor
    └── onGoalFailure() → 委托 ReflectionAgent + 记录失败案例
```

**验收标准**：
- `AgentEngine` 启动时自动注册 Wenshi 适配器（替换 NoOp 默认）
- ReAct 循环中 `critique()` 返回真实评分（非 1.0）
- `ReflexionRuntime` 反思使用真实 `ReflectionEngine`

#### 任务 1.2：Wenshi 记忆 SPI 桥接 ⭐ 最高优先级

**问题**：`MemoryStore`/`MemoryRouter` 为 NoOp，wenshi 5 类记忆真实实现未接入。

**实施方案**：

```
gewu-application/agent/adapter/
├── WenshiMemoryStoreAdapter.java       ← 新建，实现 MemoryStore
│   ├── store() → 分发到对应 wenshi 记忆服务（按 MemoryFragment.type 路由）
│   ├── retrieve() → 委托 wenshi MemoryRouter.retrieve()
│   └── clear() → 调用对应记忆服务清理
└── WenshiMemoryRouterAdapter.java      ← 新建，实现 MemoryRouter
    └── inject() → 委托 wenshi MemoryInjector.inject()
```

**类型映射**：

| MemoryFragment.type (SPI) | Wenshi 记忆服务 |
|---------------------------|----------------|
| semantic | SemanticMemoryService |
| episodic | EpisodicMemoryService |
| procedural | ProceduralMemoryService |
| parametric | ParametricMemoryService |
| experience | EpisodicMemoryService (经验存于情景记忆) |

**验收标准**：
- ReAct 循环每轮自动注入相关记忆（非空）
- 任务完成后经验自动写入情景记忆
- 记忆检索 Top-5 命中率 ≥ 80%

#### 任务 1.3：编排引擎数据库持久化

**问题**：编排图定义、执行实例、审批请求等表不存在；编排仅内存运行。

**实施方案**：

新建 Flyway 迁移脚本 `V26__orchestration_engine.sql`：

```sql
-- 编排图定义表
CREATE TABLE orchestration_graph (
    graph_id        VARCHAR(32) PRIMARY KEY,      -- ULID
    graph_name      VARCHAR(256),
    graph_definition JSONB NOT NULL,               -- DAG JSON
    state_schema    JSONB NOT NULL,
    graph_type      VARCHAR(32) NOT NULL,          -- PIPELINE/SUPERVISOR/SWARM/DEBATE
    version         VARCHAR(32) NOT NULL,
    status          VARCHAR(32) DEFAULT 'draft',
    created_by      VARCHAR(32),
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

-- 编排执行实例表
CREATE TABLE orchestration_execution (
    execution_id    VARCHAR(32) PRIMARY KEY,
    graph_id        VARCHAR(32) REFERENCES orchestration_graph,
    graph_snapshot  JSONB NOT NULL,                -- 执行时图快照
    status          VARCHAR(32) NOT NULL,          -- PENDING/RUNNING/PAUSED/SUCCEEDED/FAILED/CANCELLED
    iteration_count INT DEFAULT 0,
    token_used      BIGINT DEFAULT 0,
    cost_consumed   DECIMAL(10,4) DEFAULT 0,
    task_level      VARCHAR(8),                    -- L1/L2/L3
    result          JSONB,                         -- 最终结果
    error_message   TEXT,
    started_at      TIMESTAMPTZ DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);

-- 编排节点执行记录表
CREATE TABLE orchestration_node_execution (
    node_exec_id    VARCHAR(32) PRIMARY KEY,
    execution_id    VARCHAR(32) REFERENCES orchestration_execution,
    node_id         VARCHAR(64) NOT NULL,
    node_type       VARCHAR(32) NOT NULL,
    status          VARCHAR(32) NOT NULL,
    input           JSONB,
    output          JSONB,
    token_used      INT DEFAULT 0,
    duration_ms     BIGINT,
    error_message   TEXT,
    started_at      TIMESTAMPTZ DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);

-- 审批请求表
CREATE TABLE approval_request (
    request_id      VARCHAR(32) PRIMARY KEY,
    execution_id    VARCHAR(32) REFERENCES orchestration_execution,
    node_id         VARCHAR(64) NOT NULL,
    approval_type   VARCHAR(32) NOT NULL,          -- MANUAL_REVIEW/TAKEOVER/ROLLBACK
    payload         JSONB NOT NULL,
    status          VARCHAR(32) DEFAULT 'pending', -- pending/approved/rejected/timeout
    approver        VARCHAR(32),
    approval_comment TEXT,
    approved_at     TIMESTAMPTZ,
    timeout_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);
```

新建 MyBatis Mapper + Domain Entity + Application Service。

**验收标准**：
- 编排图可 CRUD（创建/查询/激活/版本管理）
- 编排执行实例可持久化、可查询执行历史
- 节点执行记录可追踪

#### 任务 1.4：编排 API 暴露

**问题**：`gewu-interface` 中无 `OrchestrationController`。

**实施方案**：

```
gewu-interface/.../controller/
├── OrchestrationController.java        ← 新建
│   ├── POST /api/v1/orchestration/graphs           -- 创建编排图
│   ├── GET  /api/v1/orchestration/graphs            -- 查询编排图列表
│   ├── GET  /api/v1/orchestration/graphs/{graphId}  -- 查询编排图详情
│   ├── POST /api/v1/orchestration/graphs/{graphId}/execute -- 执行编排图
│   ├── GET  /api/v1/orchestration/executions/{execId}      -- 查询执行状态
│   ├── GET  /api/v1/orchestration/executions/{execId}/stream -- SSE 流式执行
│   ├── POST /api/v1/orchestration/executions/{execId}/pause -- 暂停执行
│   ├── POST /api/v1/orchestration/executions/{execId}/resume -- 恢复执行
│   └── POST /api/v1/orchestration/executions/{execId}/cancel -- 取消执行
├── OrchestrationGoalController.java    ← 新建
│   ├── POST /api/v1/orchestration/goals             -- 提交自主目标
│   └── GET  /api/v1/orchestration/goals/{goalId}    -- 查询目标执行状态
└── ApprovalController.java             ← 新建
    ├── GET  /api/v1/approvals/pending               -- 查询待审批列表
    ├── POST /api/v1/approvals/{requestId}/approve   -- 批准
    └── POST /api/v1/approvals/{requestId}/reject    -- 驳回
```

**验收标准**：
- 通过 API 可创建编排图并执行
- SSE 流式推送执行进度（节点开始/完成/工具调用/错误）
- 暂停/恢复/取消功能可用（实现 `OrchestrationEngine` 的桩方法）

#### 任务 1.5：编排引擎 pause/resume/cancel 实现

**问题**：`OrchestrationEngine.pause()`/`resume()`/`cancel()` 为空桩。

**实施方案**：

基于执行状态机实现：
- `pause()`：设置执行状态为 PAUSED，记录当前节点，释放执行线程
- `resume()`：从 PAUSED 状态恢复，从最后执行节点继续
- `cancel()`：设置执行状态为 CANCELLED，终止所有子任务

需要引入执行注册表（`ExecutionRegistry`）管理活跃执行实例。

#### 阶段一退出标准

| 标准 | 目标值 | 验证方式 |
|------|--------|---------|
| SPI 桥接完成 | 认知+记忆共 5 个适配器 | 代码审查 + 启动日志确认非 NoOp |
| 编排 DB 表 | 4 张表创建成功 | Flyway 迁移通过 |
| 编排 API | 12 个端点可用 | API 测试通过 |
| 端到端认知闭环 | 规划→执行→验证→记忆全链路 | 集成测试：提交目标→自动规划→执行→验证→记忆写入 |
| 记忆检索命中率 | ≥ 80% | 重复任务测试 |

---

### 阶段二：认知增强与验证闭环（2-4 月）

> **目标**：在 SPI 桥接基础上，叠加双闭环验证、置信度门控、预算控制，使 Agent 具备自验证和成本约束能力。

#### 任务 2.1：双闭环验证机制

**实施方案**：

在 `gewu-agent-engine` 新增 `verification/` 包：

```
gewu-agent-engine/verification/
├── DualLoopVerifier.java              ← 新建，双闭环验证器
│   ├── innerLoop()  → 内环验证（Schema 校验 + 规则引擎 + LLM 自评估，≤5 轮）
│   ├── outerLoop()  → 外环仲裁（最强模型仲裁，≤2 轮）
│   └── shouldStop() → 收益递减检测（连续 2 轮评分不提升则停止）
├── InnerLoopResult.java
├── OuterLoopResult.java
└── VerificationConfig.java
```

内环验证流程：
1. L1 语法验证：JSON Schema 校验（已有 `SchemaValidator` 可复用）
2. L2 语义验证：`ReasoningKernel.critique()`（已桥接 wenshi Critic）+ 规则引擎
3. 失败处理：反馈修正 → 重试（≤5 轮）→ 升级外环

外环仲裁流程：
1. 触发条件：内环重试达上限或验证失败
2. 方式：最强模型仲裁 + 证据链分析
3. 失败处理：终止 → HITL

#### 任务 2.2：置信度门控

**实施方案**：

```
gewu-agent-engine/cognition/
├── ConfidenceGate.java                ← 新建
│   ├── evaluate() → 计算置信分（加权评分）
│   │   ├── 0.3 × model_self_confidence
│   │   ├── 0.25 × tool_success_rate
│   │   ├── 0.25 × historical_similarity
│   │   └── 0.2 × contract_pass_rate
│   └── decide() → GateDecision
│       ├── score ≥ 0.85 → ADOPT（采纳）
│       ├── score ≥ 0.6  → RETRY_CHANGE_MODEL（换模型重试）
│       ├── score ≥ 0.4  → RETRY_CHANGE_CONTEXT（调整上下文重试）
│       └── score < 0.4  → ESCALATE_HITL（升级人工）
```

集成到 `ReactAgentExecutor`：每轮工具调用后执行置信度评估，替代简单的循环终止判断。

#### 任务 2.3：预算控制器（BudgetController）

**实施方案**：

```
gewu-agent-engine/budget/
├── BudgetController.java              ← 新建
│   ├── checkBudget() → 四维预算检查
│   │   ├── Token 预算：累计消耗 vs 配额
│   │   ├── 时间预算：已耗时 vs 时限
│   │   ├── 成本预算：已花费 vs 预算
│   │   └── 轮次预算：当前轮次 vs 上限
│   ├── consume() → 记录消耗
│   ├── shouldDegrade() → 70% 告警 / 90% 降级 / 100% 熔断
│   └── checkCollaborationBudget() → 协作预算预检
├── BudgetContext.java                 ← 贯穿全管线的预算上下文
├── BudgetStatus.java                  ← normal/alert/degrade/block
└── BudgetExceededException.java
```

集成点：
- `ReactAgentExecutor`：每轮 LLM 调用后调用 `consume()` + `checkBudget()`
- `Orchestrator`：协作开始前调用 `checkCollaborationBudget()`
- `AutonomousExecutor`：每次迭代检查预算，替代仅检查 `maxIterations`

#### 任务 2.4：失败案例库

**实施方案**：

新建 Flyway 迁移 `V27__failure_case.sql`：

```sql
CREATE TABLE failure_case (
    case_id          VARCHAR(32) PRIMARY KEY,
    task_type        VARCHAR(64) NOT NULL,
    context_summary  TEXT NOT NULL,
    failure_point    VARCHAR(256) NOT NULL,
    root_cause       TEXT NOT NULL,
    lesson           TEXT NOT NULL,
    avoidance_rule   TEXT NOT NULL,         -- 可编码为路由规则
    confidence       FLOAT DEFAULT 0.5,
    occurrence_count INT DEFAULT 1,
    agent_id         VARCHAR(32),
    execution_id     VARCHAR(32),
    created_at       TIMESTAMPTZ DEFAULT NOW()
);
```

在 `EvolutionHook.onGoalFailure()` 中自动写入失败案例；在 `Planner.plan()` 前检索相似失败案例作为规避提示。

#### 任务 2.5：流式事件协议补全

补全 `AgentEvent` 类型，对齐架构文档流式事件协议：

| 新增事件类型 | 触发时机 |
|-------------|---------|
| `budget_warning` | 预算消耗达 70%/90% |
| `budget_exceeded` | 预算耗尽 |
| `confidence_check` | 置信度门控评估 |
| `verification_result` | 验证结果 |
| `reflection` | 反思洞察 |
| `experience_saved` | 经验写入记忆 |
| `failure_recorded` | 失败案例记录 |

#### 阶段二退出标准

| 标准 | 目标值 | 验证方式 |
|------|--------|---------|
| 双闭环验证 | 内环≤5/外环≤2 轮 | 集成测试：故意提交低质量输出，验证自动修正 |
| 验证一次通过率 | ≥ 85% | 统计指标 |
| 置信度门控 | 四级决策生效 | 低置信度任务自动升级 HITL |
| 预算熔断 | Token/时间/成本四维 | 超预算任务自动降级或终止 |
| 失败案例库 | 自动写入+检索复用 | 重复失败场景自动规避 |
| 任务成功率 | ≥ 85% | 锚点用例集测试 |

---

### 阶段三：协作增强与治理建设（4-7 月）

> **目标**：完善多 Agent 协作能力，实现 HITL 全流程，建立四环治理体系。

#### 任务 3.1：HITL 全流程实现

**实施方案**：

```
gewu-application/agent/adapter/
├── DbHitlGatewayAdapter.java           ← 新建，实现 HitlGateway
│   ├── requestApproval() → 创建 approval_request 记录，SSE 推送通知
│   ├── submitDecision() → 更新审批状态，恢复编排执行
│   └── checkTimeout() → 定时检查超时审批（5min 轮询）
├── ApprovalService.java                ← 新建
│   ├── getPendingApprovals() → 查询待审批列表
│   ├── approve() / reject() → 审批操作
│   └── notify() → IM 通知（飞书/钉钉 Webhook）
└── ApprovalTimeoutScheduler.java       ← 新建，超时自动处理
```

实现 `OrchestrationEngine.pause()` 真实逻辑：遇到 HUMAN 节点时暂停执行，创建审批请求，等待人工决策后 `resume()`。

#### 任务 3.2：冲突解决引擎

**实施方案**：

```
gewu-agent-engine/orchestration/
├── ConflictResolver.java              ← 新建
│   ├── resolve() → 按优先级使用四种策略
│   │   ├── 策略1: 权威优先（reviewer > coordinator > executor）
│   │   ├── 策略2: 多数表决（同级 Agent 投票，>50% 通过）
│   │   ├── 策略3: LLM 仲裁（同厂商多采样，n=3 取多数）
│   │   └── 策略4: 人工裁决（HITL）
│   └── resolveBatch() → 批量冲突解决
├── AgentConflict.java
├── ConflictResolution.java
└── AuthorityRank.java
```

集成到 `Orchestrator`：全局冲突检查步骤（已有占位，需填充实现）。

#### 任务 3.3：Agent 生命周期管理增强

**实施方案**：

```
gewu-agent-engine/orchestration/
├── AgentLifecycleManager.java          ← 新建/增强
│   ├── spawnAgent() → 创建 Agent 实例
│   ├── monitorAgents() → 心跳检测（30s 间隔）
│   ├── detectDeadlock() → 死锁检测（等待环检测算法）
│   ├── handleTimeout() → 超时处理（全局超时 + 强制终止）
│   └── retireAgent() → 回收 Agent（记录指标 + 释放资源）
```

#### 任务 3.4：四环协同 Phase 1-2

**实施方案**：

```
gewu-application/governance/
├── FourPhasePipeline.java              ← 新建
│   ├── executePhase() → 运算环（同步，调用 Orchestrator）
│   ├── evaluatePhase() → 评估环（异步，计算指标 + 抽样 LLM-as-Judge）
│   ├── governPhase() → 治理环（异步，策略检查 + 违规记录）
│   └── auditPhase() → 审计环（异步，写入审计存储）
```

Phase 1（单进程内四阶段同步流水线）适用于 L0-L1；Phase 2（进程间异步四环，Redis Streams 解耦）适用于 L2。本阶段先实现 Phase 1。

#### 任务 3.5：LLM-as-Judge 锚点评估

**实施方案**：

```
gewu-application/evaluation/
├── AnchorCase.java                     ← 锚点用例（input + acceptance_criteria）
├── AnchorCaseRepository.java
├── LlmJudge.java                       ← LLM 评估器（按验收标准打分 0-10）
├── EvaluationResult.java
└── SPCDegradationDetector.java         ← SPC 退化检测（控制图 + 2σ 限制）
```

关键设计：用**验收标准**（自然语言）替代期望输出，避免过拟合。

#### 任务 3.6：制品契约

**实施方案**：

```
gewu-agent-engine/contract/
├── ArtifactContract.java               ← 制品契约接口
│   ├── artifact_type: prd|design|code|test|ops
│   ├── schema: JSONSchema
│   ├── validation_rules: ValidationRule[]
│   └── dependencies: String[]
├── ArtifactValidator.java              ← 制品校验器（编排器强制调用）
└── ContractViolationException.java
```

集成到编排引擎：节点输出前强制校验制品契约，不兼容则拒绝流转。

#### 阶段三退出标准

| 标准 | 目标值 | 验证方式 |
|------|--------|---------|
| HITL 全流程 | 审批→暂停→决策→恢复 | 端到端测试 |
| 冲突解决 | 四层策略可用 | 多 Agent 冲突场景测试 |
| Agent 生命周期 | 死锁检测+超时终止 | 模拟死锁场景 |
| 四环协同 Phase 1 | 运算同步+评估/治理/审计异步 | 日志确认四环执行 |
| LLM-as-Judge | 锚点用例集 ≥ 20 条 | 评估准确率 ≥ 80% |
| 制品契约 | 校验通过率 ≥ 95% | 多阶段协作测试 |
| 多 Agent 协作 | 3+Agent 场景跑通 | 流水线/委托/审查模式各 1 场景 |
| 缺陷检出率 | ≥ 60% | 审查-迭代模式测试 |

---

### 阶段四：成本优化与自进化（7-10 月）

> **目标**：实现成本治理（模型路由+语义缓存）、经验蒸馏管道、认知调度器。

#### 任务 4.1：模型路由器（ModelRouter）

**实施方案**：

```
gewu-infrastructure/llm/
├── ModelRouter.java                    ← 新建
│   ├── route() → 路由优先级：预算→隐私→复杂度→延迟→最便宜
│   ├── assessComplexity() → 用小模型快速判断任务复杂度
│   ├── filterByLatency() → 按 SLA 延迟要求筛选候选模型
│   └── selectCheapest() → 在候选中选择成本最低的
├── RoutingContext.java
└── ModelChoice.java
```

集成到 `LlmClientRegistry`：每次 LLM 调用前经过 `ModelRouter` 决策。

#### 任务 4.2：语义缓存

**实施方案**：

```
gewu-infrastructure/cache/
├── SemanticCache.java                  ← 新建
│   ├── get() → 向量相似度查找可复用缓存（阈值 0.95）
│   ├── put() → 缓存写入（带 TTL）
│   └── determineTtl() → 按场景决定 TTL（知识问答 24h / 代码生成 1h / 默认 5min）
```

基于已有 pgvector 基础设施实现，复用 `EmbeddingAdapter`。

#### 任务 4.3：经验蒸馏管道（离线 T+1 + 人工审核）

**实施方案**：

```
gewu-application/wenshi/learning/
├── ExperienceDistillationPipeline.java ← 新建/增强
│   ├── Stage 1: 日志收集（实时 Kafka/RocketMQ）
│   ├── Stage 2: 模式提取（每日批处理，LLM 总结+聚类）
│   ├── Stage 3: 验证评估（每周批处理，回放测试+A/B 评估）
│   └── Stage 4: 知识固化（每月批处理，人工审核+写入语义/程序性记忆）
├── DistillationScheduler.java          ← 定时调度
├── PatternCandidate.java
├── ValidatedPattern.java
└── KnowledgeApproval.java              ← 人工审核工作流
```

**受控自学习原则**：禁止 Agent 自主写知识库；所有知识写入须有审批 Trace + 回滚机制。

#### 任务 4.4：认知调度器

**实施方案**：

```
gewu-application/wenshi/learning/
├── CognitiveScheduler.java             ← 新建
│   ├── runCycle() → 定时执行（每小时）
│   │   ├── 1. 质量监控（收集 success_rate/avg_latency/verification_pass_rate 等）
│   │   ├── 2. 识别改进机会（指标低于阈值时识别 gap）
│   │   ├── 3. 优先级排序（urgency × impact）
│   │   └── 4. 触发自学习（urgent→在线学习 / non-urgent→离线队列）
```

#### 任务 4.5：双系统思考模式

**实施方案**：

在编排引擎中引入 System 1/2 路由：
- System 1（快速模式）：简单/确定性任务，直接 ReAct 执行，小模型
- System 2（深度模式）：复杂/开放性任务，Plan-Execute + Reflexion，强模型

路由依据：任务复杂度评分（规则层 + 轻量分类器）。

#### 阶段四退出标准

| 标准 | 目标值 | 验证方式 |
|------|--------|---------|
| 模型路由 | 预算→隐私→复杂度→延迟→最便宜 | 路由日志确认 |
| 语义缓存 | 命中率 ≥ 10% | 缓存命中率统计 |
| 经验蒸馏 | T+1 批处理 + 人工审核 | 知识入库流程测试 |
| 认知调度器 | 定时监控 + 自动触发 | 调度日志确认 |
| 成本降低 | ≥ 30% | 对比阶段二成本基线 |
| 经验复用率 | ≥ 30% | 命中记忆的任务占比 |
| System 1 分流率 | ≥ 60% | 简单任务走快速通道占比 |

---

### 阶段五：自我进化与联邦（10 月+）

> **目标**：完整自学习闭环、WORM 审计、红蓝对抗、联邦协作探索。

#### 任务清单

| 任务 | 说明 |
|------|------|
| 5.1 WORM 审计存储 | S3 Object Lock / 链式哈希审计存储，不可篡改 |
| 5.2 在线学习探索 | 从纯离线 T+1 到受控在线学习（低风险场景） |
| 5.3 红蓝对抗 | 红队 Agent 生成攻击向量，蓝队 Agent 防御检测 |
| 5.4 知识图谱记忆 | Neo4j 合规关系追溯（L4 语义记忆增强） |
| 5.5 联邦协作探索 | 跨组织 Agent 协作，联邦学习 |
| 5.6 全链路追踪 | Jaeger 分布式追踪，OpenTelemetry 集成 |
| 5.7 多场景适配框架 | 场景适配器抽象，新场景接入 < 2 周 |

#### 阶段五退出标准

| 标准 | 目标值 |
|------|--------|
| 任务成功率 | ≥ 90% |
| HITL 率 | ≤ 10% |
| WORM 审计 | 链式哈希验证通过 |
| 新场景接入 | < 2 周 |
| 知识库污染率 | 0（人工审核保障） |

---

## 第五部分：架构决策记录（ADR）

### ADR-009：Wenshi 认知/记忆能力通过 SPI 适配器桥接至 Agent 引擎

| 属性 | 内容 |
|------|------|
| 状态 | 待批准 |
| 背景 | Wenshi 子系统已实现完整认知（Planner/Critic/SolverRouter）和记忆（5 类记忆）能力，但作为独立类存在，未实现 agent-engine 的 ReasoningKernel/MemoryStore/MemoryRouter SPI，导致引擎运行时全部 NoOp |
| 决策 | 在 `gewu-application/agent/adapter/` 新建 5 个适配器（WenshiReasoningKernelAdapter、WenshiReflectionEngineAdapter、WenshiEvolutionHookAdapter、WenshiMemoryStoreAdapter、WenshiMemoryRouterAdapter），通过 Spring `@Primary` 或 `@ConditionalOnMissingBean` 替换 NoOp 默认实现 |
| 理由 | 适配器模式零侵入复用已有实现，遵循 agent-engine 的 SPI 设计意图（设计文档 29 §10 已规划但未实现）。避免重复开发，避免修改 agent-engine 框架代码 |
| 后果 | +已有能力立即生效 +零框架改动 +适配器可独立测试 -适配器增加一层间接调用（性能可忽略） |

### ADR-010：编排引擎独立持久化，暂不与工作流引擎合并

| 属性 | 内容 |
|------|------|
| 状态 | 待批准 |
| 背景 | 存在两套编排系统：DB 驱动的工作流引擎（9 表+Controller）和内存运行的 Agent 编排引擎（4 模式+3 运行时）。架构文档主张"编排即图"统一为单一模型 |
| 决策 | 本阶段为编排引擎创建独立 DB 表（orchestration_graph/execution/node_execution/approval_request），暂不与工作流引擎合并。两套系统并行运行，通过场景适配选择使用 |
| 理由 | 合并两套系统涉及大规模重构，风险高、周期长。当前优先让编排引擎可用，合并留待 L2+ 阶段评估。工作流引擎适合确定性业务流程，编排引擎适合 AI 驱动的动态编排 |
| 后果 | +编排引擎快速可用 +降低重构风险 -两套系统并存增加认知负担 -部分功能重叠 |

### ADR-011：预算驱动替代固定轮次驱动

| 属性 | 内容 |
|------|------|
| 状态 | 待批准 |
| 背景 | 当前仅用 `maxIterations`（默认 5）和 `maxToolRounds`（默认 10）作为硬限制，无 Token/时间/成本维度预算控制，无法防止长尾任务 |
| 决策 | 实现 `BudgetController`，以四维预算（Token/时间/成本/轮次）替代固定轮次。收益递减即收敛（连续 2 轮无提升则停止），耗尽即熔断（100% 终止） |
| 理由 | 对齐架构文档核心原则（ADR-004）。固定轮次导致成本爆炸或质量不足；预算驱动实现 70% 请求秒级响应 |
| 后果 | +成本可控 +延迟可交互 +收益递减自动收敛 -深度任务质量可能略降（HITL 兜底） |

### ADR-012：置信度门控替代并行元认知监控

| 属性 | 内容 |
|------|------|
| 状态 | 待批准 |
| 背景 | 架构文档 S5 原方案"元认知监控器并行运行"持续消耗额外模型调用，成本倍增 50%+ |
| 决策 | 实现 `ConfidenceGate`，按需触发（任务失败后/复杂任务完成后/置信分持续低位时），替代并行监控。四级决策（adopt/retry_model/retry_context/escalate_hitl） |
| 理由 | 对齐架构文档 ADR-003。可工程化、成本可控、回避元认知开放问题 |
| 后果 | +成本可控 +按需触发 -路由精度依赖评分模型质量 |

### ADR-013：受控自学习，禁止 Agent 自主写知识库

| 属性 | 内容 |
|------|------|
| 状态 | 待批准 |
| 背景 | Agent 自主写知识库存在错误知识传染风险；wenshi `SkillEvolver` 已有经验→技能固化能力但缺少人工审核门控 |
| 决策 | 经验蒸馏采用离线 T+1 模式，所有知识写入须经人工审核入库。Agent 可写入"候选模式"，但不可直接写入"已验证知识" |
| 理由 | 对齐架构文档 ADR-005。杜绝错误知识传染，确保知识质量 |
| 后果 | +知识质量可靠 -知识更新延迟（T+1，可接受） |

---

## 第六部分：风险评估与对策

| 风险类型 | 表现 | 概率 | 影响 | 对策 |
|----------|------|------|------|------|
| **SPI 桥接兼容性问题** | Wenshi 类型与 agent-engine SPI 接口不兼容 | 中 | 高 | 编写适配器前先做接口对齐分析；适配器做类型转换层；集成测试覆盖 |
| **编排引擎持久化复杂度** | 图模型序列化/反序列化、执行恢复状态管理复杂 | 中 | 高 | 使用 JSONB 存储图快照；执行恢复基于 checkpoint 机制；先实现简单场景再增强 |
| **双轨系统认知负担** | 工作流引擎与编排引擎并存，开发人员混淆 | 高 | 中 | 编写使用指南明确场景边界；长期规划合并路线 |
| **预算控制精度** | Token 计数不准确导致预算误判 | 中 | 中 | 使用 LLM 返回的 usage 字段；对未返回 usage 的供应商做估算 |
| **LLM-as-Judge 评估偏差** | 评估 LLM 与执行 LLM 同质化，评分虚高 | 中 | 中 | 评估使用不同供应商模型；定期人工校准；SPC 检测退化 |
| **多 Agent 死锁** | Agent 间循环等待导致任务挂起 | 中 | 高 | 全局超时 + 心跳检测 + 等待环检测算法 + 强制终止 |
| **HITL 响应延迟** | 人工审批不及时导致任务长时间暂停 | 高 | 中 | 超时自动处理（默认拒绝）；IM 通知推送；SLA 监控 |
| **成本失控** | 多 Agent 协作 Token 乘数效应 | 高 | 高 | 协作预算预检 + 模型路由 + 语义缓存 + System 1 分流 |
| **过度工程化** | 一次性追求完整认知架构 | 中 | 高 | 严格按五阶段渐进；每阶段充分验证后再升级；L0 够用就不上 L1 |
| **知识库污染** | 错误知识写入导致后续任务全部受影响 | 中 | 高 | 禁止自主写库；T+1 离线蒸馏 + 人工审核；回滚机制 |

---

## 第七部分：优先级矩阵

### 7.1 影响力 × 紧迫性矩阵

```
     高影响
       │
   ┌───┼───────────────────┐
   │   │  ① 立即执行         │
   │   │  · SPI 桥接(1.1)   │
   │   │  · 记忆桥接(1.2)   │
   │   │  · 编排DB(1.3)     │
   │   │  · 编排API(1.4)   │
   │   │  · 预算控制(2.3)   │
   ────┼───────────────────┼──── 高紧迫
   │   │  ② 计划执行         │
   │   │  · 双闭环(2.1)     │
   │   │  · 置信门控(2.2)   │
   │   │  · HITL(3.1)      │
   │   │  · 冲突解决(3.2)   │
   │   │  · 四环治理(3.4)   │
   ────┼───────────────────┼──── 
   │   │  ③ 适时执行         │
   │   │  · 模型路由(4.1)   │
   │   │  · 语义缓存(4.2)   │
   │   │  · 经验蒸馏(4.3)   │
   │   │  · LLM-Judge(3.5) │
   │   │  · 制品契约(3.6)   │
   ────┼───────────────────┼──── 低紧迫
   │   │  ④ 延后执行         │
   │   │  · WORM审计(5.1)   │
   │   │  · 红蓝对抗(5.3)   │
   │   │  · 联邦协作(5.5)   │
   │   │  · 在线学习(5.2)   │
   └───┴───────────────────┘
     低影响
```

### 7.2 推荐执行顺序

**第一优先级（立即）**——解锁已有能力：
1. 任务 1.1 Wenshi 认知 SPI 桥接
2. 任务 1.2 Wenshi 记忆 SPI 桥接
3. 任务 1.3 编排引擎 DB 持久化
4. 任务 1.4 编排 API 暴露

**第二优先级（紧随）**——工程基础设施：
5. 任务 1.5 pause/resume/cancel 实现
6. 任务 2.3 预算控制器
7. 任务 2.4 失败案例库
8. 任务 2.5 流式事件补全

**第三优先级（认知增强）**：
9. 任务 2.1 双闭环验证
10. 任务 2.2 置信度门控

**第四优先级（协作治理）**：
11. 任务 3.1 HITL 全流程
12. 任务 3.2 冲突解决引擎
13. 任务 3.4 四环协同
14. 任务 3.5 LLM-as-Judge
15. 任务 3.6 制品契约

**第五优先级（成本自进化）**：
16. 任务 4.1 模型路由
17. 任务 4.2 语义缓存
18. 任务 4.3 经验蒸馏管道
19. 任务 4.4 认知调度器

---

## 附录：技术栈对照

| 架构文档推荐 | 当前项目使用 | 差距 | 阶段 |
|-------------|------------|------|------|
| LangGraph（编排） | 自研 `OrchestrationEngine`（Java） | 无差距（自研对齐 LangGraph 理念） | - |
| PostgreSQL + pgvector | OceanBase（主库）+ PostgreSQL 16（Wenshi） | 无差距 | - |
| Redis（工作/短期记忆） | Redis（已有 Redisson） | 需配置工作记忆 TTL | P1 |
| Milvus（向量记忆） | pgvector（HNSW 索引） | 无差距（pgvector 满足 L1-L2） | - |
| Redis Streams（事件日志） | RocketMQ（已有） | 无差距（RocketMQ 更强） | - |
| Langfuse（Agent 可观测） | 自研 SSE + 审计 | 需引入 Langfuse 或自研 Agent 看板 | P2+ |
| Prometheus + Grafana | 已有部署配置 | 无差距 | - |
| OpenTelemetry + Jaeger | 未接入 | 需引入 | P4+ |
| Docker（开发沙箱） | 已有 | 无差距 | - |
| Firecracker μVM（生产沙箱） | 仅 Docker | 需引入 | P3+ |
| K8s + Helm | 已有 K8s 配置 | 需补充 Helm Chart | P2+ |

---

> **结语**：本实施计划基于 codebase-memory 知识图谱对 gewu-platform 的深度分析，结合《认知型 Agent 智能体架构设计技术文档（综合实施版）》的目标架构，制定了五阶段渐进式实施路线。核心策略是"先连通再增强"——优先桥接 Wenshi 认知/记忆能力到 Agent 引擎 SPI，让已投入开发的能力真正生效，再逐步叠加验证闭环、预算控制、多 Agent 协作、成本优化和自进化能力。每阶段设定明确的退出标准，确保渐进式推进，避免过度工程化。

---

## 附录E：实施完成状态（V2.0 更新）

### 阶段一：SPI 桥接与编排落地 ✅ 已完成

| 任务 | 状态 | 交付物 |
|------|------|--------|
| 1.1 Wenshi 认知 SPI 桥接 | ✅ | `WenshiReasoningKernelAdapter`（Planner/Critic/SolverRouter → ReasoningKernel） |
| 1.2 Wenshi 记忆 SPI 桥接 | ✅ | `WenshiMemoryStoreAdapter` + `WenshiMemoryRouterAdapter`（5 类记忆 → MemoryStore/MemoryRouter） |
| 1.3 编排引擎 DB 持久化 | ✅ | V26 迁移（4 表）+ 4 实体 + 4 Mapper + `OrchestrationService` |
| 1.4 编排 API 暴露 | ✅ | `OrchestrationController`（13 端点）+ `ApprovalController`（3 端点） |
| 1.5 pause/resume/cancel | ✅ | 服务层状态机 + 流式 doOnCancel 自动清理 |
| — 反思引擎适配器 | ✅ | `WenshiReflectionEngineAdapter`（LLM 反思 → ReflectionEngine） |
| — 进化钩子适配器 | ✅ | `WenshiEvolutionHookAdapter`（记忆写入 → EvolutionHook） |

### 阶段二：认知增强与验证闭环 ✅ 已完成

| 任务 | 状态 | 交付物 |
|------|------|--------|
| 2.1 双闭环验证 | ✅ | `DualLoopVerifier`（内环≤5/外环≤2 + 收益递减检测） |
| 2.2 置信度门控 | ✅ | `ConfidenceGate` + `GateDecision`（四级决策 ADOPT/RETRY/ESCALATE） |
| 2.3 预算控制器 | ✅ | `BudgetContext` + `BudgetController`（四维 Token/时间/成本/轮次） |
| 2.4 失败案例库 | ✅ | V27 迁移 + `FailureCaseEntity` + Mapper + `FailureCaseService` |
| 2.5 流式事件补全 | ✅ | AgentEvent 新增 7 种事件类型 |

### 阶段三：协作增强与治理建设 ✅ 已完成

| 任务 | 状态 | 交付物 |
|------|------|--------|
| 3.1 HITL 全流程 | ✅ | `DbHitlGatewayAdapter`（审批表+SSE+Sinks.One 阻塞恢复） |
| 3.2 冲突解决引擎 | ✅ | `ConflictResolver`（四层：权威>表决>仲裁>HITL） |
| 3.3 Agent 生命周期管理 | ✅ | `AgentLifecycleManager`（心跳+死锁DFS+超时终止） |
| 3.4 四环协同 Phase 1 | ✅ | `FourPhasePipeline`（Execute同步+Evaluate/Govern/Audit异步） |
| 3.5 LLM-as-Judge | ✅ | `LlmJudge`（锚点评估，验收标准替代期望输出） |
| 3.5 SPC 退化检测 | ✅ | `SPCDegradationDetector`（控制图+2σ+连续7次下降检测） |
| 3.6 制品契约 | ✅ | `ArtifactContract` + `ArtifactValidator`（两级校验 L1语法+L2规则） |

### 阶段四：成本优化与自进化 ✅ 已完成

| 任务 | 状态 | 交付物 |
|------|------|--------|
| 4.1 模型路由 | ✅ | `ModelRouter`（预算→隐私→复杂度→延迟→最便宜） |
| 4.2 语义缓存 | ✅ | `SemanticCache`（向量相似度≥0.95 命中，pgvector 复用） |
| 4.3 经验蒸馏管道 | ✅ | `ExperienceDistillationPipeline`（每日提取+每周验证+人工审核固化） |
| 4.4 认知调度器 | ✅ | `CognitiveScheduler`（每小时监控+识别改进+触发自学习） |
| 4.5 双系统思考 | ✅ | `DualSystemRouter`（规则+轻量分类，System 1 REACT/System 2 PLAN_EXECUTE） |

### 阶段五：自我进化与联邦 🔶 部分完成

| 任务 | 状态 | 交付物 |
|------|------|--------|
| 5.1 WORM 审计存储 | ✅ | V28 迁移（audit_chain 表）+ `AuditChainEntity`+Mapper+`AuditChainService`（链式SHA-256）+ `AuditChainController`（3端点） |
| 5.2 在线学习探索 | ⏳ 远期 | 需从纯离线 T+1 逐步过渡 |
| 5.3 红蓝对抗 | ⏳ 远期 | 需独立红蓝 Agent + 攻击向量库 |
| 5.4 知识图谱记忆 | ⏳ 远期 | 需引入 Neo4j |
| 5.5 联邦协作 | ⏳ 远期 | 需跨组织协议设计 |
| 5.6 全链路追踪 | ⏳ 远期 | 需引入 OpenTelemetry + Jaeger |
| 5.7 多场景适配框架 | ✅ | `ScenarioAdapter` 接口 + `ScenarioAdapterRegistry` + 4 个内置场景（默认/代码生成/智能问答/架构设计） |

### 实施统计

| 维度 | 数量 |
|------|------|
| 新建 Java 文件 | ~35 |
| 新建 SQL 迁移 | 3（V26/V27/V28） |
| 新建 DB 表 | 7（orchestration_graph/execution/node_execution/approval_request/failure_case/audit_chain） |
| 新增 API 端点 | 19（Orchestration 13 + Approval 3 + AuditChain 3） |
| 修改引擎文件 | 5（ReactAgentExecutor/AutonomousExecutor/AgentEngineAutoConfiguration/AgentEvent/GewuApplication） |
| SPI 适配器 | 6（ReasoningKernel/ReflectionEngine/EvolutionHook/MemoryStore/MemoryRouter/HitlGateway） |
| 新增引擎组件 | 17（BudgetController/ConfidenceGate/DualLoopVerifier/ConflictResolver/AgentLifecycleManager/ArtifactContract/ArtifactValidator/DualSystemRouter/ModelRouter/SemanticCache/FourPhasePipeline/LlmJudge/SPCDegradationDetector/ExperienceDistillationPipeline/CognitiveScheduler/ScenarioAdapterRegistry/AuditChainService） |
| 编译状态 | ✅ 零错误 |
| 架构成熟度 | L0→L1 →**L2+ 增强 Agent** |

### 未实施项（需后续迭代）

| 项目 | 原因 | 建议 |
|------|------|------|
| 在线学习探索 | 架构文档 ADR-005 坚持受控离线模式 | T+1 已够用，探索阶段再考虑 |
| 红蓝对抗 | 需独立安全团队投入 | 安全审计阶段评估 |
| 知识图谱记忆（Neo4j） | L2 阶段 pgvector 满足需求 | L3 联邦阶段引入 |
| 联邦协作 | 需跨组织协议 | 多组织场景触发 |
| OpenTelemetry + Jaeger | 需引入独立运维组件 | 规模化部署时引入 |
| Firecracker μVM 沙箱 | 当前 Docker 满足开发需求 | 生产高安全场景引入 |
