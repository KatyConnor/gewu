# 格物平台 Agent 编排引擎架构设计

> **文档编号**：29  
> **版本**：v1.0  
> **日期**：2026-08-06  
> **状态**：草案  
> **关联文档**：`21-unified-architecture.md`、`27-agent-sandbox-design.md`、`28-workflow-engine-design.md`、`docs/wenshi/WENSHI-ARCHITECTURE-v3.md`

---

## 1. 设计目标与原则

### 1.1 设计目标

本架构为格物平台设计一套 **Agent 编排引擎体系（Agent Orchestration Engine, AOE）**，需同时满足以下五大能力场景：

| # | 能力场景 | 设计诉求 |
|---|---------|---------|
| 1 | **软件开发全生命周期** | 覆盖需求→设计→开发→审查→测试→部署→运维→反馈的端到端 Agent 协作 |
| 2 | **人机协同** | 关键节点人工审批、实时接管、协同编辑、异步通知 |
| 3 | **自主目标驱动** | 给定高层目标，自主分解、规划、执行、验证、收敛 |
| 4 | **多 Agent 模式** | Supervisor 路由、专家流水线、Swarm 交接、辩论共识 |
| 5 | **自我进化学习** | 经验沉淀、反思复盘、技能演化、评测驱动的能力提升闭环 |

### 1.2 设计原则

1. **复用优先，增量演进**——不另起炉灶，在现有 `AgentExecutionEngine` + `Wenshi` + 工作流引擎之上抽象扩展。
2. **编排统一**——将"工作流 DAG 编排"与"Agent ReAct 循环"统一为同一编排模型，消除双轨。
3. **策略可插拔**——Agent 类型、编排模式、执行模式均以策略接口暴露，DB 配置驱动 + 代码注册双轨。
4. **认知闭环**——每次执行都产生推理轨迹与经验，回流 Wenshi 学习层，形成"执行→反思→进化"闭环。
5. **安全可控**——人机协同审批、工具权限分级、沙箱隔离、全链路审计、国密合规贯穿始终。
6. **流式优先**——所有 Agent 输出走 Reactor `Flux`，SSE 实时回推，适配长推理与多轮工具调用。

---

## 2. 现状分析与演进策略

### 2.1 已有可复用资产

| 资产 | 位置 | 复用方式 |
|------|------|---------|
| Agent 执行引擎 | `gewu-application/.../agent/AgentExecutionEngine.java` | 作为 Runtime 的 ReAct 执行器被编排引擎调用 |
| 工具执行服务 | `gewu-application/.../agent/ToolExecutionService.java` | 作为能力层，新增 ToolRegistry 代码级注册 |
| LLM 客户端工厂 | `gewu-infrastructure/.../llm/LlmClientFactory.java` | 直接复用，DB 驱动多供应商 |
| MCP 集成 | `gewu-infrastructure/.../mcp/` | 作为工具通道之一 |
| 沙箱服务 | `gewu-sandbox` | 作为 code_execute 工具后端 |
| Wenshi 知识层 | `gewu-application/.../wenshi/*MemoryService` | 作为记忆体系后端 |
| Wenshi 推理层 | `Planner/SolverRouter/Critic/MultiAgentCoordinator` | 作为编排引擎的认知内核 |
| Wenshi 学习层 | `ExperienceExtractor/SkillEvolver/ReflectionAgent` | 作为自我进化引擎 |
| 工作流引擎 | `workflow` 9 表 + React Flow 画布 | 编排引擎的 DAG 执行器后端 |
| pgvector | `wenshi_*` 6 表 | 记忆/经验/轨迹向量检索 |

### 2.2 现有局限（演进点）

| 局限 | 现状 | 演进方向 |
|------|------|---------|
| Agent 无类型/策略 | `agent` 表贫血，仅存 prompt/模型配置 | 新增 `agent_type`/`orchestration_mode`/`execution_mode` 字段 + 角色注册表 |
| 单 Agent 单循环 | `AgentExecutionEngine` 无多 Agent 协调 | 引入 Orchestrator，统一调度多 Agent |
| 双轨编排 | 工作流引擎 ⟂ Agent 引擎 | 统一为"编排图 = Agent 节点 + 工具节点 + 人工节点" |
| 工具仅配置驱动 | `agent_tool` 表 endpoint/schema | 新增 `@ToolProvider` 代码级注册 + DB 配置双轨 |
| 无自主目标循环 | 无 Goal Planner + Autonomous Loop | 新增 GoalDecomposer + AutonomousExecutor |
| 无 HITL 节点 | 工作流有审批但与 Agent 脱节 | 引入 HumanNode + 审批网关 |
| 学习层未闭环 | Wenshi 学习层独立运行 | 将反思/经验抽取嵌入执行后置钩子 |

---

## 3. 总体架构

### 3.1 分层视图

```
╔══════════════════════════════════════════════════════════════════╗
║                        交互层 (Interface)                         ║
║   Web Console · REST API · SSE Stream · Webhook · IDE 插件        ║
╠══════════════════════════════════════════════════════════════════╣
║                   人机协同网关 (HITL Gateway)                      ║
║   审批队列 · 实时接管 · 协同编辑 · 异步通知 · 接管/回退              ║
╠══════════════════════════════════════════════════════════════════╣
║          ★ Agent 编排引擎 (Orchestration Engine) ★                ║
║                                                                   ║
║   ┌─────────────┐  ┌──────────────┐  ┌────────────────────────┐  ║
║   │ Goal Planner│→ │ Orchestrator │→ │   Execution Scheduler   │  ║
║   │ 目标分解 DAG │  │ 模式路由/调度 │  │ 并行/串行/有界并发/重试  │  ║
║   └─────────────┘  └──────┬───────┘  └───────────┬────────────┘  ║
║                           │                       │               ║
║        ┌──────────────────┼───────────────────────┘               ║
║        ▼                  ▼                                       ║
║  ┌──────────┐  ┌──────────────┐  ┌───────────┐  ┌───────────┐    ║
║  │Supervisor│  │  Pipeline    │  │  Swarm    │  │  Debate   │    ║
║  │  路由模式 │  │  流水线模式  │  │ 交接模式  │  │ 辩论模式  │    ║
║  └──────────┘  └──────────────┘  └───────────┘  └───────────┘    ║
╠══════════════════════════════════════════════════════════════════╣
║               SDLC Agent 角色层 (Role Registry)                   ║
║                                                                   ║
║  需求PM · 架构师 · 开发者 · 代码审查 · 测试工程师 · DevOps         ║
║  SRE运维 · 安全审计 · 文档工程师 · 数据工程师 · ...                ║
╠══════════════════════════════════════════════════════════════════╣
║                Agent 运行时 (Agent Runtime)                       ║
║                                                                   ║
║  ┌─────────┐ ┌─────────────┐ ┌───────────┐ ┌──────────────────┐  ║
║  │  ReAct  │ │Plan-Execute │ │ Reflexion │ │  Tool Parallel   │  ║
║  │ 执行器  │ │  执行器     │ │  执行器   │ │   执行器         │  ║
║  └─────────┘ └─────────────┘ └───────────┘ └──────────────────┘  ║
║         ▲ 基于 AgentExecutionEngine 扩展, Flux<AgentChunk> 流式    ║
╠══════════════════════════════════════════════════════════════════╣
║          认知与进化层 (Cognition & Evolution) ← 复用 Wenshi        ║
║                                                                   ║
║  ┌─── 记忆体系 ───┐ ┌─── 推理内核 ───┐ ┌─── 进化引擎 ──────────┐ ║
║  │ 语义·情景·程序性│ │Planner·Solver  │ │ExperienceExtractor    │ ║
║  │ ·参数化记忆     │ │Router·Critic   │ │SkillEvolver           │ ║
║  │ MemoryRouter    │ │MultiAgentCoord │ │ReflectionAgent        │ ║
║  └─────────────────┘ └────────────────┘ │EvaluationRunner       │ ║
║                                       └───────────────────────┘ ║
║          经验库 · 推理轨迹 · 技能演化 · 评测 · 知识摄取             ║
╠══════════════════════════════════════════════════════════════════╣
║            能力层 (Capability: Tool & Sandbox)                    ║
║                                                                   ║
║  ToolRegistry(代码注册+DB配置) │ MCP │ HTTP Tools │ Code Sandbox  ║
║  Git/GitHub CLI │ CI/CD 调用 │ DB 工具 │ 文档生成 │ Web 搜索       ║
╠══════════════════════════════════════════════════════════════════╣
║                 基础设施 (Infrastructure)                         ║
║  LlmClientFactory │ pgvector │ MySQL/OceanBase │ RocketMQ         ║
║  DragonflyDB │ MinIO │ SearXNG │ Prometheus │ Audit │ 国密SM2/3/4 ║
╚══════════════════════════════════════════════════════════════════╝
```

### 3.2 核心设计理念：编排即图

统一编排模型的核心是 **"一切皆编排图节点"**：

```
编排图 (OrchestrationGraph) = 有向图
节点类型 (NodeType):
  ├── AgentNode     → 委派给某个 SDLC 角色 Agent 执行
  ├── ToolNode      → 直接调用工具(无 LLM 推理)
  ├── HumanNode     → 人机协同审批/输入节点
  ├── RouterNode    → 条件分支(LLM 路由 or 规则路由)
  ├── ParallelNode  → 并行扇出
  ├── MergeNode     → 汇聚合并
  └── SubGraph      → 嵌套子编排图(递归)
```

工作流引擎的 DAG 变为编排图的一种特例（以 ToolNode/HumanNode/RouterNode 为主），Agent 引擎的 ReAct 循环变为 AgentNode 内部的执行策略。**双轨统一为单轨。**

---

## 4. 核心引擎：Agent 编排引擎

### 4.1 编排模型统一

#### 4.1.1 核心抽象

```java
// 领域层: gewu-domain/.../orchestration/

/** 编排图 */
public class OrchestrationGraph {
    private String graphId;          // ULID
    private String name;
    private GraphType type;          // SDLC_PIPELINE / GOAL_DECOMPOSED / AD_HOC
    private List<GraphNode> nodes;
    private List<GraphEdge> edges;
    private Map<String, Object> variables;   // 图级变量(全局上下文)
    private String rootGoalId;       // 关联的自主目标(若有)
}

/** 图节点 */
public class GraphNode {
    private String nodeId;
    private NodeType type;           // AGENT / TOOL / HUMAN / ROUTER / PARALLEL / MERGE / SUBGRAPH
    private String refId;            // 引用: agentId / toolName / approvalConfigId / subGraphId
    private NodeConfig config;       // 节点配置(执行模式/超时/重试/并发度)
    private Map<String, Object> inputs;   // 输入映射(可引用图变量 ${var.xxx})
}

/** 图边 */
public class GraphEdge {
    private String fromNode;
    private String toNode;
    private String condition;        // 路由条件(表达式/规则)
}
```

#### 4.1.2 编排引擎接口

```java
// 应用层: gewu-application/.../orchestration/

public interface OrchestrationEngine {

    /** 提交编排图执行(同步) */
    OrchestrationResult execute(OrchestrationGraph graph, OrchestrationContext ctx);

    /** 提交编排图执行(流式) */
    Flux<OrchestrationEvent> executeStream(OrchestrationGraph graph, OrchestrationContext ctx);

    /** 自主目标驱动执行(给定高层目标,自动分解+执行) */
    Flux<OrchestrationEvent> executeGoal(AutonomousGoal goal, OrchestrationContext ctx);

    /** 暂停/恢复/取消(支持 HITL) */
    void pause(String executionId);
    void resume(String executionId, HumanDecision decision);
    void cancel(String executionId);
}
```

### 4.2 Goal Planner（目标分解器）

自主目标驱动的入口。将高层目标分解为编排图。

```java
public interface GoalPlanner {
    /**
     * 目标分解: 高层目标 → PlanTree(DAG)
     * 策略: 经验库匹配(成功目标复用) → LLM 规划 → Critic 验证
     */
    OrchestrationGraph decompose(AutonomousGoal goal, OrchestrationContext ctx);
}

public class AutonomousGoal {
    private String goalId;
    private String description;        // "为 gewu-platform 实现 OAuth2 登录模块"
    private GoalType type;             // FEATURE / BUGFIX / REFACTOR / RESEARCH / OPS
    private List<String> constraints;  // 约束: "必须用 Spring Security" / "不可改动用户表"
    private List<AcceptanceCriteria> acceptances;  // 验收标准(可验证)
    private String domain;             // 所属领域(SDLC 阶段)
    private Integer maxIterations;     // 自主循环上限(防失控)
    private BigDecimal budgetTokens;   // Token 预算
}
```

**分解策略（三级路由，复用 Wenshi SolverRouter 思路）**：

```
Goal → [1.经验库匹配] ──命中──→ 复用历史 PlanTree(微调)
                  │
                  └─未命中──→ [2.LLM Planner 规划] → PlanTree 草案
                                              │
                                              ▼
                                     [3.Critic 验证] ──不通过──→ 回到2(限3轮)
                                              │
                                              └─通过──→ OrchestrationGraph
```

### 4.3 Orchestrator（编排器）

编排引擎的核心调度器，负责按编排图驱动执行。

```java
public class Orchestrator {

    private final Map<OrchestrationMode, ModeHandler> modeHandlers;
    private final ExecutionScheduler scheduler;
    private final HitlGateway hitlGateway;
    private final EvolutionHook evolutionHook;

    public Flux<OrchestrationEvent> run(OrchestrationGraph graph, OrchestrationContext ctx) {
        return Flux.create(sink -> {
            // 1. 拓扑排序确定执行顺序
            // 2. 按节点类型分派:
            //    AGENT     → AgentRuntime.execute()
            //    TOOL      → ToolExecutionService.invoke()
            //    HUMAN     → hitlGateway.requestApproval() (阻塞/异步)
            //    ROUTER    → 评估 condition 选择分支
            //    PARALLEL  → scheduler.fanOut() 并行执行子节点
            //    MERGE     → 汇聚多分支结果
            //    SUBGRAPH  → 递归 run()
            // 3. 每个节点产出写入图变量,供下游引用
            // 4. 节点完成后触发 evolutionHook.onNodeComplete()
            // 5. 全图完成触发 evolutionHook.onGraphComplete()
        });
    }
}
```

### 4.4 编排模式（四种）

#### 模式一：Supervisor / Router（监督者路由）

中央 Supervisor Agent 决定任务分派给哪个专家 Agent，汇总结果。

```
                 ┌──→ 需求PM Agent
Goal/Task ──→ Supervisor ──→ 架构师 Agent
                 ├──→ 开发者 Agent
                 └──→ 测试 Agent
                       │
                 ←── 汇总/裁决 ←──
```

**适用**：任务边界不清晰、需要动态决策分派。Supervisor 本身是一个 LLM Agent，每轮输出"分派给谁 + 子任务描述"。

```java
public class SupervisorModeHandler implements ModeHandler {
    // Supervisor 持有所有子 Agent 的能力描述(capability card)
    // 每轮: LLM(prompt=任务+子Agent能力卡) → 输出 route(agentId, subTask)
    // 子 Agent 执行后结果回传 Supervisor → 决定继续分派 or 结束
}
```

#### 模式二：Pipeline（专家流水线）

固定顺序的串行流水线，每个 Agent 处理一个 SDLC 阶段，产出传递给下一个。

```
需求PM → 架构师 → 开发者 → 代码审查 → 测试 → DevOps → SRE
 (PRD)  (设计)   (代码)    (Review)  (测试) (部署)  (监控)
```

**适用**：SDLC 全流程贯通、流程明确的标准化交付。每个节点的产出(文档/代码/报告)作为下一节点输入。

#### 模式三：Swarm / Handoff（群体交接）

轻量级 Agent 间控制权传递，无中心节点。Agent 自主判断"我完成了/该交出去了"并 handoff。

```
开发者 Agent ──handoff──→ 代码审查 Agent ──handoff──→ 测试 Agent
(写完代码,判定需审查)      (审完,判定需测试)            (测完,判定需部署)
```

**适用**：探索性、边界动态变化的任务。每个 Agent 携带共享的 TaskContext，handoff 时附带交接说明。

```java
public class SwarmModeHandler implements ModeHandler {
    // 当前活跃 Agent 执行后输出: Handoff{toAgent, reason, contextSummary}
    // 若输出 FINISH → 结束; 若输出 HANDOFF → 切换到目标 Agent
    // 防环: 记录 handoff 链, 同一 Agent 连续 handoff 超阈值则强制终止
}
```

#### 模式四：Debate（辩论共识）

多个 Agent 持不同立场/方案辩论，由 Critic/裁判 Agent 汇总最优方案。

```
        ┌──→ 方案A Agent ──┐
问题 ───┤──→ 方案B Agent ──┤──→ 裁判 Agent ──→ 最优方案
        └──→ 方案C Agent ──┘    (Critic接地验证)
```

**适用**：架构选型、技术方案评审等需要多视角权衡的高风险决策。

### 4.5 编排模式选择策略

```java
public enum OrchestrationMode {
    SUPERVISOR,   // 动态分派,边界不清
    PIPELINE,     // 固定流程,SDLC贯通
    SWARM,        // 探索性,动态交接
    DEBATE        // 多方案权衡决策
}

// 自动推荐: GoalPlanner 根据目标特征推荐模式
//   - type=FEATURE & 流程明确  → PIPELINE
//   - type=RESEARCH/边界不清   → SWARM 或 SUPERVISOR
//   - type=技术选型/架构决策    → DEBATE
// 用户可在 Web Console 覆盖推荐
```

---

## 5. SDLC Agent 角色体系

### 5.1 角色定义

覆盖软件开发全生命周期的角色 Agent 注册表。每个角色 = (系统提示词 + 能力卡 + 工具集 + 记忆域 + 编排位置)。

| 阶段 | 角色 Agent | 职责 | 核心工具 | 记忆域 |
|------|-----------|------|---------|--------|
| **需求** | 需求PM Agent | 需求澄清、PRD 撰写、用户故事拆分、优先级排序 | 文档生成、Web 搜索、需求库检索 | 需求模式记忆 |
| **需求** | 需求评审 Agent | 需求完整性/一致性/可行性评审 | 需求库、历史评审经验 | 评审经验 |
| **设计** | 架构师 Agent | 技术选型、架构设计、API 契约、ADR | 架构知识库、代码库检索、Web 搜索 | 架构决策记忆 |
| **设计** | 数据库设计 Agent | 数据模型、表结构、索引、迁移脚本 | DB Schema 检索、SQL 执行(只读) | 数据模型记忆 |
| **开发** | 开发者 Agent | 编码实现、单元测试、本地调试 | 代码沙箱、Git、代码库检索 | 编码模式记忆 |
| **开发** | 重构 Agent | 代码异味识别、安全重构、技术债治理 | 代码库检索、AST 分析、沙箱 | 重构经验 |
| **审查** | 代码审查 Agent | Code Review、规范检查、安全漏洞 | 代码库、规范库、SAST 工具 | 审查经验 |
| **审查** | 安全审计 Agent | 渗透测试、合规检查、漏洞修复建议 | SAST/DAST、国密校验、合规库 | 安全经验 |
| **测试** | 测试工程师 Agent | 测试策略、用例设计、自动化脚本 | 测试框架、沙箱、覆盖率工具 | 测试模式记忆 |
| **测试** | QA Agent | 端到端测试、回归、验收测试 | E2E 框架、UI 自动化 | QA 经验 |
| **部署** | DevOps Agent | CI/CD 流水线、容器化、部署编排 | Git、Docker、K8s、Jenkins | 部署经验 |
| **运维** | SRE Agent | 监控告警、故障排查、容量规划 | Prometheus、日志检索、Web 搜索 | 故障案例记忆 |
| **运维** | 文档工程师 Agent | API 文档、架构文档、变更日志 | 文档生成、代码库检索 | 文档模板记忆 |

### 5.2 SDLC 全流程编排矩阵

不同交付场景的编排组合：

```
场景A: 标准功能开发 (PIPELINE)
  需求PM → 架构师 → 开发者 → 代码审查 → 测试 → DevOps → SRE
  │         │        │          │         │       │       │
  PRD      设计文档   代码+单测   Review    测试报告  部署   监控配置

场景B: 紧急 Bug 修复 (SWARM)
  SRE(定位) ─handoff→ 开发者(修复) ─handoff→ 代码审查 ─handoff→ DevOps(热修部署)

场景C: 架构升级决策 (DEBATE)
  架构师A(方案微服务) vs 架构师B(方案模块化单体) → 裁判(Critic) → ADR

场景D: 技术债治理 (SUPERVISOR)
  Supervisor → [重构Agent / 测试Agent / 文档Agent] 按债优先级分派

场景E: 自主目标 "实现 OAuth2 登录" (GOAL_DECOMPOSED)
  GoalPlanner 分解 → PIPELINE(需求PM→架构师→开发者→审查→测试→DevOps)
                    → 验收标准校验 → 不通过则 Reflexion 回退重做
```

### 5.3 角色实现（AgentSpec）

```java
// 领域层: gewu-domain/.../agent/role/

public class AgentRoleSpec {
    private String roleCode;            // "DEVELOPER"
    private String roleName;            // "开发者"
    private SdlcPhase phase;            // REQUIREMENT / DESIGN / DEVELOP / REVIEW / TEST / DEPLOY / OPS
    private String systemPromptTemplate;// 系统提示词(含变量占位)
    private List<String> toolNames;     // 绑定工具
    private List<String> skillCodes;    // 绑定技能
    private String memoryDomain;        // 记忆域隔离
    private ExecutionMode defaultMode;  // 默认执行模式
    private CapabilityCard capability;  // 能力卡(供 Supervisor 路由决策)
}

public record CapabilityCard(
    String summary,                    // "负责编码实现、单元测试、本地调试"
    List<String> capabilities,         // ["Java/Spring Boot", "重构", "调试"]
    List<String> inputSchema,          // 接受的输入类型 ["设计文档","需求"]
    List<String> outputSchema          // 产出类型 ["源代码","单元测试"]
) {}
```

角色通过 **DB 配置（`agent_role` 表）+ 代码注册（`@RoleProvider`）** 双轨管理。

---

## 6. Agent 运行时（Agent Runtime）

### 6.1 执行模式（四种策略）

```java
public enum ExecutionMode {
    REACT,          // Thought→Action→Observation 循环(现有)
    PLAN_EXECUTE,   // 先规划任务列表再逐步执行
    REFLEXION,      // 执行后反思→改进→重试
    TOOL_PARALLEL   // 多工具并行调用(现有 AgentExecutionEngine 已支持)
}

public interface AgentRuntime {
    Flux<AgentChunk> execute(AgentTask task, AgentRuntimeContext ctx);
}
```

### 6.2 扩展现有 AgentExecutionEngine

现有 `AgentExecutionEngine` 作为 `REACT` + `TOOL_PARALLEL` 执行器。新增 `PlanExecuteRuntime` 和 `ReflexionRuntime`：

```
AgentRuntime (接口)
  ├── ReactRuntime          = 现有 AgentExecutionEngine (复用)
  ├── PlanExecuteRuntime    = 新增: Planner 拆任务 → 逐个 ReactRuntime → 汇总
  ├── ReflexionRuntime      = 新增: ReactRuntime → Critic 评估 → 不过则反思→重做(限N轮)
  └── CompositeRuntime      = 组合: 按节点 config 选策略
```

**关键：Runtime 不重写，而是组合**。`PlanExecuteRuntime` 内部调用 `ReactRuntime` 执行每个子任务；`ReflexionRuntime` 内部调用 `ReactRuntime` + Wenshi `Critic`。复用现有循环逻辑。

### 6.3 流式事件协议（扩展现有）

现有 chunk 事件类型：`status/thinking/content/tool_call/tool_executing/tool_result/done/error`。新增编排层事件：

| 事件类型 | 含义 |
|---------|------|
| `graph_start` | 编排图开始 |
| `node_start` | 节点开始(含 nodeId/agentRole) |
| `node_complete` | 节点完成(含产出摘要) |
| `handoff` | Swarm 模式 Agent 交接 |
| `approval_required` | HITL 审批请求 |
| `approval_result` | 审批结果 |
| `goal_decomposed` | 目标分解完成(含 PlanTree) |
| `reflection` | 反思触发 |
| `experience_saved` | 经验已沉淀 |
| `graph_complete` | 编排图完成 |

---

## 7. 人机协同机制（HITL）

### 7.1 HITL 网关

```java
// 应用层: gewu-application/.../hitl/

public interface HitlGateway {

    /** 请求人工审批(阻塞当前节点,异步等待) */
    Flux<OrchestrationEvent> requestApproval(ApprovalRequest req);

    /** 人工提交决策(恢复执行) */
    void submitDecision(String approvalId, HumanDecision decision);

    /** 人工接管(直接控制 Agent 执行) */
    void takeover(String executionId, String operatorId);

    /** 人工回退到某节点 */
    void rollback(String executionId, String toNodeId);
}

public class ApprovalRequest {
    private String approvalId;
    private String executionId;
    private String nodeId;
    private ApprovalType type;         // APPROVE_REJECT / INPUT / SELECT / EDIT
    private String summary;            // Agent 产出摘要
    private Object artifact;           // 待审产物(代码/文档/部署计划)
    private List<ApprovalOption> options;
    private Duration timeout;          // 超时策略
    private EscalationPolicy escalation; // 超时升级
}
```

### 7.2 协同模式

| 模式 | 触发 | 行为 |
|------|------|------|
| **审批门** | 高风险节点(部署/付款/删除) | Agent 暂停，人工 APPROVE/REJECT 后继续 |
| **输入门** | 需求澄清/方案选择 | Agent 暂停，人工输入后继续 |
| **协同编辑** | 文档/代码产出 | Agent 产出后人工可修改，修改作为反馈注入 |
| **实时接管** | Agent 卡住/出错 | 人工接管控制权，手动驱动后交回 |
| **回退重做** | 产出不满意 | 人工指定回退到某节点，修改输入后重跑 |
| **异步通知** | 长任务完成/需审批 | Webhook/IM 推送，人工异步处理 |

### 7.3 HumanNode 实现

```java
public class HumanNodeHandler implements NodeHandler {
    public Flux<OrchestrationEvent> handle(GraphNode node, OrchestrationContext ctx) {
        ApprovalRequest req = buildRequest(node, ctx);
        // 暂停编排, 写入 approval_queue 表, 推送通知
        // 返回 Flux 在 submitDecision 到达时 emit
        return hitlGateway.requestApproval(req)
            .map(decision -> toEvent(decision));
    }
}
```

**审批持久化**：新增 `approval_request` 表，记录请求/决策/操作人/时间，支持跨会话异步审批。配合 RocketMQ 延迟消息实现超时升级。

---

## 8. 自主目标驱动（Autonomous Goal Loop）

### 8.1 自主执行循环

```
                ┌──────────────────────────────────┐
                ▼                                   │
  ┌──────────────────────┐                          │
  │ 1. Goal 接收         │                          │
  └──────────┬───────────┘                          │
             ▼                                       │
  ┌──────────────────────┐                          │
  │ 2. Goal Decompose    │← 经验库匹配 / LLM 规划   │
  │    → OrchestrationGraph                         │
  └──────────┬───────────┘                          │
             ▼                                       │
  ┌──────────────────────┐                          │
  │ 3. Orchestrate       │← Supervisor/Pipeline/... │
  │    (含 HITL 节点)    │                          │
  └──────────┬───────────┘                          │
             ▼                                       │
  ┌──────────────────────┐                          │
  │ 4. Verify            │← 验收标准校验             │
  │    Acceptance Criteria                         │
  └──────────┬───────────┘                          │
             ▼                                       │
        ┌─────────┐                                  │
        │ 通过?   │──否──→ Reflexion(反思+重规划)──→ ┘
        └────┬────┘       (限 maxIterations 轮)
             │是
             ▼
  ┌──────────────────────┐
  │ 5. Finalize          │→ 经验沉淀 → 进化引擎
  │    交付 + 经验抽取    │
  └──────────────────────┘
```

### 8.2 防失控机制

自主循环必须设边界，避免无限消耗：

| 机制 | 实现 |
|------|------|
| **迭代上限** | `maxIterations`(默认5)，超出强制停止并请求人工介入 |
| **Token 预算** | `budgetTokens`，超出暂停并请求追加预算审批 |
| **时间预算** | 单图执行超时(默认30min)，超时暂停 |
| **HITL 强制节点** | 关键阶段(部署/线上变更)强制插入审批门 |
| **回退限制** | Reflexion 重做同一节点限3次，超出升级人工 |
| **资源配额** | 沙箱/工具调用次数配额，超限熔断 |
| **心跳与可观测** | 每节点心跳上报 Prometheus，异常自动告警 |

```java
public class AutonomousExecutor {
    public Flux<OrchestrationEvent> executeGoal(AutonomousGoal goal) {
        return Flux.create(sink -> {
            int iteration = 0;
            OrchestrationGraph graph = goalPlanner.decompose(goal, ctx);
            while (iteration < goal.getMaxIterations()) {
                var result = orchestrator.run(graph, ctx).blockLast();
                if (verifier.verify(result, goal.getAcceptances())) {
                    evolutionHook.onGoalSuccess(goal, result);  // 经验沉淀
                    sink.next(doneEvent(result));
                    return;
                }
                // 未通过 → 反思重规划
                graph = reflexionEngine.replan(goal, result, graph);
                iteration++;
            }
            // 超出迭代 → 请求人工介入
            hitlGateway.requestHumanIntervention(goal, "超出自主迭代上限");
        });
    }
}
```

---

## 9. 自我进化学习闭环（Evolution Loop）

### 9.1 进化引擎架构

复用并增强 Wenshi 学习层，形成"执行→反思→沉淀→演化→评测"闭环：

```
┌─────────────────────────────────────────────────────────────┐
│                    自我进化引擎 (Evolution Engine)            │
│                                                              │
│  ┌──────────┐   ┌───────────┐   ┌──────────┐   ┌─────────┐ │
│  │ 执行轨迹 │→  │  反思     │→  │ 经验抽取 │→  │ 技能演化│ │
│  │ Trace    │   │ Reflection│   │ Extract  │   │ Evolve  │ │
│  └──────────┘   └───────────┘   └──────────┘   └────┬────┘ │
│                                                      │      │
│  ┌──────────────────────────────────────────────────▼────┐ │
│  │              经验库 (wenshi_experience)                │ │
│  │  scenario_hash · 策略 · outcome · score · lesson      │ │
│  └───────────────────────┬──────────────────────────────┘ │
│                          │                                  │
│  ┌───────────────────────▼──────────────────────────────┐ │
│  │              技能库 (wenshi_procedural_memory)         │ │
│  │  skill JSON · usage_count · success_rate · skill_level│ │
│  └───────────────────────┬──────────────────────────────┘ │
│                          │                                  │
│  ┌───────────────────────▼──────────────────────────────┐ │
│  │              评测驱动 (EvaluationRunner)              │ │
│  │  离线 benchmark · 回归测试 · A/B · 能力基线           │ │
│  └──────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### 9.2 进化触发点（EvolutionHook）

嵌入编排引擎的生命周期钩子，自动触发进化：

```java
public class EvolutionHook {

    /** 节点完成后: 记录推理轨迹 */
    void onNodeComplete(GraphNode node, NodeResult result) {
        // 写入 wenshi_reasoning_trace (plan_tree/trace_steps/token_stats)
        reasoningTraceService.record(node, result);
    }

    /** 图完成后: 反思 + 经验抽取 */
    void onGraphComplete(OrchestrationGraph graph, OrchestrationResult result) {
        // 1. 反思: ReflectionAgent 评估整体执行
        ReflectionOutcome reflection = reflectionAgent.reflect(graph, result);
        // 2. 经验抽取: ExperienceExtractor 提取可复用经验
        List<Experience> experiences = experienceExtractor.extract(graph, result, reflection);
        experienceStore.saveAll(experiences);  // 写入 wenshi_experience
        // 3. 质量评估: QualityAssessor 打分
        double score = qualityAssessor.assess(result, reflection);
        // 4. 技能演化: 高分经验升级为技能, 低分技能降级/淘汰
        skillEvolver.evolve(experiences, score);
    }

    /** 目标失败后: 失败经验抽取(避免重蹈覆辙) */
    void onGoalFailure(AutonomousGoal goal, OrchestrationResult result) {
        experienceExtractor.extractFailure(goal, result);
    }
}
```

### 9.3 技能演化机制

```java
public class SkillEvolver {
    /**
     * 经验→技能的演化规则:
     *  - 同类经验累计 ≥3 次且平均 score ≥ 0.7 → 固化为技能(procedural_memory)
     *  - 技能 success_rate 持续下降 → 降级 skill_level
     *  - 技能长期未使用(usage_count 停滞) → 标记待淘汰
     *  - 技能可被 GoalPlanner 复用(经验库匹配时直接套用)
     */
    void evolve(List<Experience> experiences, double score) {
        // 聚合同类经验 → 评估 → 升级/降级/淘汰技能
    }
}
```

### 9.4 评测驱动改进

```java
public class EvaluationRunner {
    /**
     * 离线评测:
     *  - 维护 SDLC 各角色的 benchmark 用例集(需求分析/编码/测试/审查...)
     *  - 定期(或技能演化后)跑评测, 生成能力雷达图
     *  - 能力退化则回滚技能版本
     *  - 评测结果驱动 prompt/工具配置迭代
     */
    EvaluationReport evaluate(String roleCode) {
        // 跑 benchmark → 与历史基线对比 → 生成报告
    }
}
```

---

## 10. 认知与记忆体系（复用 Wenshi）

### 10.1 记忆体系映射

直接复用 Wenshi 四类记忆，按 Agent 角色域隔离：

| 记忆类型 | Wenshi 服务 | Agent 用途 | 检索时机 |
|---------|------------|-----------|---------|
| 语义记忆 | `SemanticMemoryService` | 领域知识、架构规范、代码模式 | 任务开始时注入 |
| 情景记忆 | `EpisodicMemoryService` | 历史执行事件、协作经历 | 相似场景检索 |
| 程序性记忆 | `ProceduralMemoryService` | 技能库(演化产物) | 任务匹配时套用 |
| 参数化记忆 | `ParametricMemoryService` | 用户偏好、项目配置 | 全程可用 |
| 经验库 | `wenshi_experience` | 成功/失败经验 | GoalPlanner 分解时匹配 |

### 10.2 记忆注入流程

```
AgentTask 到达
   │
   ▼
MemoryRouter(规则优先+LLM兜底)
   │  判断: 本任务需要哪些记忆?
   ▼
MemoryInjector
   │  检索 → 去重 → 截断(token预算) → 注入 system prompt
   ▼
AgentRuntime 执行(带记忆上下文)
   │
   ▼
执行产出 → 更新情景记忆 + 推理轨迹
```

### 10.3 多租户与隔离

- 所有记忆表带 `tenant_id`（Wenshi 已实现 RLS 行级安全）
- Agent 角色记忆域隔离：`memory_domain` 字段，避免跨角色污染
- 敏感记忆(密钥/凭据)不入向量库，走参数化记忆加密存储

---

## 11. 能力层：工具与沙箱

### 11.1 统一工具注册（双轨）

```java
// 基础设施层: gewu-infrastructure/.../tool/

/** 代码级工具注册(注解驱动) */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolProvider {
    String name();
    String description();
    String category();   // GIT / CI_CD / DB / DOC / SEARCH / CODE / OPS
}

public interface Tool {
    ToolDefinition getDefinition();   // JSON Schema 参数定义
    ToolResult invoke(Map<String, Object> args, ToolContext ctx);
}

/** 工具注册中心: 启动时扫描 @ToolProvider + DB agent_tool 表 */
public class ToolRegistry {
    private final Map<String, Tool> codeTools;       // 代码注册
    private final Map<String, ToolConfig> dbTools;   // DB 配置(MCP/HTTP)

    public Tool resolve(String toolName) { ... }
    public List<ToolDefinition> allDefinitions() { ... }
}
```

### 11.2 SDLC 专用工具集

| 类别 | 工具 | 说明 |
|------|------|------|
| Git | `git_clone/commit/push/branch/pr` | 代码版本操作 |
| CI/CD | `trigger_pipeline/get_build_status` | Jenkins/GitLab CI 调用 |
| DB | `execute_sql_readonly/query_schema` | 只读 SQL + Schema 检索 |
| 代码 | `search_code/analyze_ast/refactor` | 代码库检索 + AST 分析(对接 codebase-memory) |
| 沙箱 | `code_execute` | 复用 gewu-sandbox 执行任意代码 |
| 文档 | `generate_doc/extract_doc` | Apache POI 文档生成 |
| 搜索 | `web_search` | SearXNG + LLM 仲裁 |
| 监控 | `query_metrics/query_logs` | Prometheus + 日志检索 |
| K8s | `kubectl_apply/get_pods/rollout` | K8s 操作(高权限,强制 HITL) |

### 11.3 安全管线（复用并增强）

复用现有 `ToolExecutionService` 安全管线，增强编排层控制：

```
工具调用 → [1.ToolSchemaValidator 参数校验]
         → [2.PermissionEvaluationService 权限评估]
         → [3.高危操作检测: K8s/DB写/删除 → 强制 HumanNode]
         → [4.SsrfValidator + 沙箱扫描]
         → [5.执行(超时/重试/并发控制)]
         → [6.输出截断 + AuditLogService 审计]
```

---

## 12. 数据模型扩展

### 12.1 `agent` 表扩展字段

```sql
ALTER TABLE agent ADD COLUMN agent_type VARCHAR(32) DEFAULT 'STANDARD'
  COMMENT 'Agent类型: STANDARD/ROLE/SUPERVISOR/ORCHESTRATOR';
ALTER TABLE agent ADD COLUMN role_code VARCHAR(64) NULL
  COMMENT 'SDLC角色编码(角色Agent)';
ALTER TABLE agent ADD COLUMN orchestration_mode VARCHAR(32) NULL
  COMMENT '编排模式: SUPERVISOR/PIPELINE/SWARM/DEBATE';
ALTER TABLE agent ADD COLUMN execution_mode VARCHAR(32) DEFAULT 'REACT'
  COMMENT '执行模式: REACT/PLAN_EXECUTE/REFLEXION/TOOL_PARALLEL';
ALTER TABLE agent ADD COLUMN memory_domain VARCHAR(64) NULL
  COMMENT '记忆域隔离';
ALTER TABLE agent ADD COLUMN capability_card JSON NULL
  COMMENT '能力卡(供Supervisor路由)';
```

### 12.2 新增表

```sql
-- SDLC 角色注册表
CREATE TABLE agent_role (
  id VARCHAR(26) NOT NULL PRIMARY KEY,
  role_code VARCHAR(64) NOT NULL UNIQUE,
  role_name VARCHAR(128) NOT NULL,
  sdlc_phase VARCHAR(32) NOT NULL COMMENT 'REQUIREMENT/DESIGN/DEVELOP/REVIEW/TEST/DEPLOY/OPS',
  system_prompt_template TEXT,
  tool_names JSON COMMENT '绑定工具名列表',
  skill_codes JSON COMMENT '绑定技能编码',
  memory_domain VARCHAR(64),
  default_execution_mode VARCHAR(32),
  capability_card JSON,
  status TINYINT DEFAULT 1,
  deleted TINYINT DEFAULT 0,
  created_at BIGINT, updated_at BIGINT, created_by VARCHAR(26), updated_by VARCHAR(26)
);

-- 编排图定义
CREATE TABLE orchestration_graph (
  id VARCHAR(26) NOT NULL PRIMARY KEY,
  name VARCHAR(256) NOT NULL,
  graph_type VARCHAR(32) NOT NULL COMMENT 'SDLC_PIPELINE/GOAL_DECOMPOSED/AD_HOC/TEMPLATE',
  orchestration_mode VARCHAR(32) NOT NULL,
  nodes JSON NOT NULL COMMENT '节点定义数组',
  edges JSON NOT NULL COMMENT '边定义数组',
  variables JSON COMMENT '图级变量模板',
  version INT DEFAULT 1,
  status TINYINT DEFAULT 1,
  deleted TINYINT DEFAULT 0,
  created_at BIGINT, updated_at BIGINT, created_by VARCHAR(26), updated_by VARCHAR(26)
);

-- 编排执行实例
CREATE TABLE orchestration_execution (
  id VARCHAR(26) NOT NULL PRIMARY KEY,
  graph_id VARCHAR(26) NOT NULL,
  graph_snapshot JSON NOT NULL COMMENT '执行时图快照',
  goal_id VARCHAR(26) NULL COMMENT '关联自主目标',
  status VARCHAR(32) NOT NULL COMMENT 'RUNNING/PAUSED/APPROVAL_WAITING/SUCCESS/FAILED/CANCELLED',
  current_node_id VARCHAR(64) NULL,
  context JSON COMMENT '执行上下文(图变量)',
  result JSON NULL COMMENT '最终结果',
  iteration INT DEFAULT 0 COMMENT '自主循环迭代数',
  token_used BIGINT DEFAULT 0,
  started_at BIGINT, completed_at BIGINT,
  error_message TEXT NULL,
  tenant_id VARCHAR(26),
  deleted TINYINT DEFAULT 0,
  created_at BIGINT, updated_at BIGINT, created_by VARCHAR(26), updated_by VARCHAR(26),
  INDEX idx_graph(graph_id), INDEX idx_status(status), INDEX idx_tenant(tenant_id)
);

-- 自主目标
CREATE TABLE autonomous_goal (
  id VARCHAR(26) NOT NULL PRIMARY KEY,
  description TEXT NOT NULL,
  goal_type VARCHAR(32) NOT NULL COMMENT 'FEATURE/BUGFIX/REFACTOR/RESEARCH/OPS',
  constraints JSON,
  acceptances JSON COMMENT '验收标准列表',
  domain VARCHAR(64),
  max_iterations INT DEFAULT 5,
  budget_tokens BIGINT,
  status VARCHAR(32) NOT NULL COMMENT 'PENDING/DECOMPOSING/EXECUTING/VERIFYING/SUCCESS/FAILED',
  execution_id VARCHAR(26) NULL,
  tenant_id VARCHAR(26),
  deleted TINYINT DEFAULT 0,
  created_at BIGINT, updated_at BIGINT, created_by VARCHAR(26), updated_by VARCHAR(26)
);

-- HITL 审批请求
CREATE TABLE approval_request (
  id VARCHAR(26) NOT NULL PRIMARY KEY,
  execution_id VARCHAR(26) NOT NULL,
  node_id VARCHAR(64) NOT NULL,
  approval_type VARCHAR(32) NOT NULL COMMENT 'APPROVE_REJECT/INPUT/SELECT/EDIT',
  summary TEXT,
  artifact JSON COMMENT '待审产物',
  options JSON,
  status VARCHAR(32) NOT NULL COMMENT 'PENDING/APPROVED/REJECTED/EXPIRED',
  decision JSON NULL COMMENT '人工决策内容',
  operator_id VARCHAR(26) NULL COMMENT '审批人',
  timeout_seconds INT,
  expires_at BIGINT,
  tenant_id VARCHAR(26),
  created_at BIGINT, updated_at BIGINT, resolved_at BIGINT,
  INDEX idx_execution(execution_id), INDEX idx_status(status)
);
```

### 12.3 复用现有表（不改动）

- `wenshi_experience` / `wenshi_procedural_memory` / `wenshi_reasoning_trace` 等记忆与经验表
- `agent_tool` / `mcp_server` 工具配置
- `skill` 技能表
- `workflow_*` 工作流表（编排引擎可导出为 graph，向后兼容）

---

## 13. 核心 API 设计

### 13.1 编排引擎 API

```
# 提交编排图执行(流式)
POST /api/v1/orchestrations/{graphId}/execute
Accept: text/event-stream
→ SSE: graph_start / node_start / content / tool_* / approval_required / ... / graph_complete

# 自主目标驱动
POST /api/v1/goals
{ "description": "实现OAuth2登录", "type": "FEATURE", "acceptances": [...] }
→ 202 { "goalId": "01J...", "executionId": "01J..." }
→ SSE 订阅: GET /api/v1/goals/{goalId}/stream

# HITL 审批
GET  /api/v1/approvals/pending            # 待审列表
POST /api/v1/approvals/{id}/decision      # 提交决策
POST /api/v1/orchestrations/{execId}/takeover   # 人工接管
POST /api/v1/orchestrations/{execId}/rollback   # 回退节点

# 编排控制
POST /api/v1/orchestrations/{execId}/pause
POST /api/v1/orchestrations/{execId}/resume
POST /api/v1/orchestrations/{execId}/cancel

# SDLC 角色管理
GET  /api/v1/agent-roles
POST /api/v1/agent-roles                  # 注册角色
POST /api/v1/agent-roles/{code}/tools     # 绑定工具

# 进化与评测
GET  /api/v1/evolution/metrics            # 能力雷达图
POST /api/v1/evolution/evaluate/{role}    # 触发评测
GET  /api/v1/experiences                  # 经验库检索
```

### 13.2 SSE 事件示例

```
event: graph_start
data: {"executionId":"01J...","graphId":"01J...","mode":"PIPELINE"}

event: node_start
data: {"nodeId":"n1","role":"REQUIREMENT_PM","agentId":"01J..."}

event: thinking
data: {"content":"分析需求..."}

event: content
data: {"content":"# OAuth2登录 PRD\n..."}

event: node_complete
data: {"nodeId":"n1","artifact":{"type":"DOC","ref":"01J..."}}

event: approval_required
data: {"approvalId":"01J...","nodeId":"n4","summary":"部署到生产环境,请审批"}

event: approval_result
data: {"approvalId":"01J...","decision":"APPROVED","operator":"u001"}

event: experience_saved
data: {"experienceId":"01J...","score":0.85,"lesson":"OAuth2 + Spring Security 标准模式"}

event: graph_complete
data: {"executionId":"01J...","status":"SUCCESS","tokenUsed":128500}
```

---

## 14. 安全与治理

### 14.1 安全分层

| 层 | 措施 |
|----|------|
| 身份与租户 | JWT + 多租户 tenant_id 隔离（已有） |
| Agent 权限 | `agent_permission` + `PermissionEvaluationService` 分级评估（已有，增强） |
| 工具权限 | 高危工具(K8s/DB写/删除)强制 HumanNode 审批 |
| 沙箱隔离 | `gewu-sandbox` Docker 隔离 + `SandboxCodeScanner` 危险操作扫描（已有） |
| 数据安全 | 国密 SM2/SM3/SM4 + API Key 加密存储（已有） |
| 自主循环 | 迭代上限 + Token/时间预算 + 强制 HITL 节点 |
| 审计 | 全链路 `AuditLogService` + `audit_log` 表（已有，编排事件入审） |
| 合规 | 等保2.0三级 + 数据不出域(本地嵌入 + 私有部署 LLM) |

### 14.2 自主循环安全红线

- **生产环境变更**：必须 HumanNode 审批，禁止 Agent 自主部署到生产
- **数据删除**：任何 delete/drop 操作强制审批
- **外部网络**：Agent 出站请求经 SsrfValidator + 白名单
- **凭据使用**：密钥/Token 不进入 LLM 上下文，由工具层注入
- **资源消耗**：单目标 Token 预算上限，超出熔断并告警

---

## 15. 关键 ADR

### ADR-001: 统一编排模型（消除工作流与Agent双轨）

- **状态**：已批准
- **背景**：现有工作流引擎(DAG)与Agent引擎(ReAct)是两套并行体系，概念重叠、维护成本高、无法组合。
- **决策**：引入"编排图"统一抽象，工作流节点和Agent循环都是图节点，统一由 Orchestrator 调度。
- **理由**：消除双轨；支持 Agent+工作流混合编排；向后兼容(工作流可导出为图)。
- **后果**：需新增编排引擎；工作流引擎降级为图执行器后端；短期有迁移成本。

### ADR-002: 编排模式四选一（Supervisor/Pipeline/Swarm/Debate）

- **状态**：已批准
- **背景**：多Agent协作有多种模式，单一模式无法覆盖所有场景。
- **决策**：实现四种模式作为 ModeHandler 策略，GoalPlanner 按目标特征推荐，用户可覆盖。
- **理由**：四种模式覆盖动态分派/固定流程/探索交接/多方案决策四类场景；策略可插拔易扩展。
- **后果**：四种模式各自实现，需保证 TaskContext 在模式间一致。

### ADR-003: 进化闭环嵌入编排钩子（非独立运行）

- **状态**：已批准
- **背景**：Wenshi 学习层当前独立运行，与主Agent引擎脱节，进化数据不闭环。
- **决策**：通过 EvolutionHook 将反思/经验抽取/技能演化嵌入编排引擎生命周期(onNodeComplete/onGraphComplete/onGoalFailure)。
- **理由**：每次执行自动产生进化数据，无需额外触发；闭环天然形成。
- **后果**：编排引擎依赖 Wenshi 学习层；需控制钩子异步执行不阻塞主流程。

### ADR-004: 自主循环强制设界（防失控）

- **状态**：已批准
- **背景**：自主目标驱动有失控风险(无限循环/资源耗尽/误操作)。
- **决策**：迭代上限+Token预算+时间预算+强制HITL+回退限制+资源配额六重边界。
- **理由**：Agent 自主性与安全性必须平衡，边界触发即升级人工。
- **后果**：部分目标可能因边界中断，需人工介入；安全性优先。

---

## 16. 演进路线

### Phase 1: 编排内核（4周）

- [ ] 编排图模型 + Orchestrator + ExecutionScheduler
- [ ] 统一现有 AgentExecutionEngine 为 ReactRuntime
- [ ] `agent` 表扩展字段 + `orchestration_graph`/`orchestration_execution` 表
- [ ] PIPELINE 模式（最常用，先落地）
- [ ] SSE 编排事件协议
- **交付**：可跑通"需求PM→开发者→审查"流水线

### Phase 2: 多Agent模式 + SDLC角色（4周）

- [ ] SDLC 角色注册表 + `agent_role` 表 + 12个角色预置
- [ ] SUPERVISOR 模式 + SWARM 模式 + DEBATE 模式
- [ ] `@ToolProvider` 代码级工具注册 + SDLC 工具集(Git/CI/DB/代码检索)
- [ ] 角色能力卡 + Supervisor 路由
- **交付**：多Agent协作覆盖SDLC全流程

### Phase 3: 人机协同 + 自主目标（4周）

- [ ] HITL Gateway + `approval_request` 表 + HumanNode
- [ ] 审批/接管/回退/异步通知
- [ ] GoalPlanner + AutonomousExecutor + `autonomous_goal` 表
- [ ] 验收标准校验器 + 防失控边界
- **交付**：给定目标自主分解执行 + 关键节点人工审批

### Phase 4: 自我进化闭环（4周）

- [ ] EvolutionHook 嵌入编排生命周期
- [ ] 对接 Wenshi 反思/经验抽取/技能演化
- [ ] EvaluationRunner 离线评测 + 能力雷达图
- [ ] 经验库匹配复用(GoalPlanner)
- **交付**：执行→反思→沉淀→演化→评测闭环

### Phase 5: 治理与优化（持续）

- [ ] 编排图可视化编辑器(复用 React Flow 画布)
- [ ] Token/成本看板
- [ ] 编排模板市场
- [ ] 跨租户经验共享(可选)
- [ ] 性能优化(并行调度/缓存/流式背压)

---

## 附录 A：模块归属（DDD 分层）

| 层 | 包 | 内容 |
|----|----|------|
| 领域层 | `gewu-domain/.../orchestration` | OrchestrationGraph/GraphNode/AutonomousGoal/AgentRoleSpec |
| 领域层 | `gewu-domain/.../agent/role` | 角色/CapabilityCard 领域模型 |
| 应用层 | `gewu-application/.../orchestration` | OrchestrationEngine/Orchestrator/GoalPlanner/ModeHandler |
| 应用层 | `gewu-application/.../agent/runtime` | AgentRuntime/ReactRuntime/PlanExecuteRuntime/ReflexionRuntime |
| 应用层 | `gewu-application/.../hitl` | HitlGateway/ApprovalService |
| 应用层 | `gewu-application/.../evolution` | EvolutionHook/EvaluationRunner(对接Wenshi) |
| 基础设施层 | `gewu-infrastructure/.../tool` | ToolRegistry/@ToolProvider/SDLC工具实现 |
| 接口层 | `gewu-interface/.../orchestration` | OrchestrationController/GoalController/ApprovalController(SSE) |

## 附录 B：与现有模块的关系

```
现有                              新增
─────────                        ─────────
AgentExecutionEngine  ──封装为──→ ReactRuntime
ToolExecutionService  ──被调用──→ ToolRegistry / Orchestrator
LlmClientFactory      ──被调用──→ AgentRuntime
workflow 引擎          ──降级为──→ 图执行器后端(向后兼容)
Wenshi 知识层          ──被复用──→ MemoryRouter/Injector
Wenshi 推理层          ──被复用──→ GoalPlanner / Critic
Wenshi 学习层          ──被复用──→ EvolutionHook
gewu-sandbox           ──被调用──→ code_execute 工具
```
