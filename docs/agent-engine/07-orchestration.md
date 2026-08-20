# 07 · 编排引擎

> 单 Agent 解决"一次推理 + 工具调用"；真实交付需要多个 Agent 按流程协作、有人介入、能反思重做。格物 Agent 引擎在 `com.gewu.agent.engine.orchestration` 提供"编排即图"模型：把工作流 DAG 与 Agent ReAct 统一为一张编排图，由 `Orchestrator` 调度四种多 Agent 模式、三种执行运行时，并由 `AutonomousExecutor` 驱动自主目标循环。本篇是编排引擎的完整参考。

[![Java 21](https://img.shields.io/badge/Java-21-orange)]() [![Reactor](https://img.shields.io/badge/Reactor-Flux-blue)]()

## 7.1 编排即图

"编排即图"是引擎的核心设计理念：工作流节点（调用工具、人工审批、路由、并行、合并、子图）与 Agent 推理循环在编排层都是图上的同构节点，由同一个 `Orchestrator` 调度。这消除了"工作流引擎"与"Agent 框架"两套并行的执行模型。

```
   传统双轨：                          统一为编排图：
   ┌───────────────┐                   ┌─ AGENT (ReAct/Plan/Reflexion)
   │ 工作流引擎     │  消除             ├─ TOOL  (直接调工具)
   │  (BPMN/DAG)   │ ─────►  GraphNode ├─ HUMAN (审批/输入)
   ├───────────────┤                   ├─ ROUTER(条件分支)
   │ Agent 框架     │                   ├─ PARALLEL(扇出)
   │  (ReAct loop) │                   ├─ MERGE  (汇聚)
   └───────────────┘                   └─ SUBGRAPH(嵌套子图)
```

## 7.2 OrchestrationGraph 数据模型

| 模型 | 关键字段 | 说明 |
|------|----------|------|
| `OrchestrationGraph` | `graphId` / `name` / `type` / `mode` / `nodes` / `edges` / `variables` / `rootGoalId` | 一张编排图，`mode` 决定 `ModeHandler`，`variables` 为图级全局上下文 |
| `GraphNode` | `nodeId` / `type` / `refId` / `roleCode` / `executionMode` / `config` / `inputs` | `refId` 指向 agentId / toolName / approvalConfigId / subGraphId；`roleCode` 用于解析 `AgentRoleSpec` |
| `GraphEdge` | `fromNode` / `toNode` / `condition` | `condition` 供 ROUTER 节点做条件路由 |
| `OrchestrationContext` | `executionId` / `graphId` / `userId` / `tenantId` / `sessionId` / `variables` / `currentNodeId` / `iteration` | 贯穿一次执行的运行时状态，`putVariable`/`getVariable` 读写图变量 |
| `OrchestrationResult` | `executionId` / `status` / `outputs` / `finalOutput` / `errorMessage` / `tokenUsed` / `durationMs` | 同步执行结果，`status` ∈ SUCCESS/FAILED/PAUSED/CANCELLED |
| `AutonomousGoal` | `goalId` / `description` / `type` / `constraints` / `acceptances` / `domain` / `maxIterations` / `budgetTokens` / `status` | 自主目标入口，`maxIterations` 默认 5 |

节点类型 `NodeType` 取值：`AGENT` / `TOOL` / `HUMAN` / `ROUTER` / `PARALLEL` / `MERGE` / `SUBGRAPH`。编排模式 `OrchestrationMode`：`SUPERVISOR` / `PIPELINE` / `SWARM` / `DEBATE`。执行模式 `ExecutionMode`：`REACT` / `PLAN_EXECUTE` / `REFLEXION` / `TOOL_PARALLEL`。图类型 `GraphType`：`SDLC_PIPELINE` / `GOAL_DECOMPOSED` / `AD_HOC` / `TEMPLATE`。`AutonomousGoal.type` 为字符串：`FEATURE` / `BUGFIX` / `REFACTOR` / `RESEARCH` / `OPS`。

## 7.3 架构总览

```
                              OrchestrationEngine (门面)
                                     │
                  ┌──────────────────┼──────────────────┐
                  ▼                  ▼                  ▼
             Orchestrator       GoalPlanner      AutonomousExecutor
            (图调度/分派)       (目标→图)        (分解→执行→验收→反思重做)
                  │
        ┌─────────┴─────────┬───────────┬─────────────┐
        ▼                   ▼           ▼             ▼
  PipelineModeHandler  SupervisorModeHandler SwarmModeHandler DebateModeHandler
   (拓扑串行)            (中央分派)          (自主handoff)     (并行辩论+裁判)
        │  每个 AGENT 节点委托 AgentRuntime.execute(AgentTask)
        ▼
  ┌─────────────┬────────────────┬────────────────┐
  │ ReactRuntime│ PlanExecuteRuntime│ ReflexionRuntime │
  └──────┬──────┴─────────┬───────┴────────┬───────┘
         └────────────────┴────────────────┘
                          │ 委托
                          ▼
                   AgentExecutor (ReAct 执行器)
                          ▼
                   LlmClientRegistry + ToolExecutor
```

## 7.4 OrchestrationEngine 门面

```java
public class OrchestrationEngine {
    public Flux<AgentEvent> executeStream(OrchestrationGraph graph, OrchestrationContext ctx);  // 流式
    public OrchestrationResult execute(OrchestrationGraph graph, OrchestrationContext ctx);      // 同步
    public Flux<AgentEvent> executeGoal(AutonomousGoal goal);                                     // 自主目标
    public void pause(String executionId);   public void resume(String executionId);  public void cancel(String executionId);
}
```

`executeStream` 与 `execute` 委托 `Orchestrator`；`executeGoal` 委托 `AutonomousExecutor`；`pause`/`resume`/`cancel` 为配合外部调度（如基于 `ExecutionRecord` 状态机）的占位接口。

## 7.5 Orchestrator 调度器

`Orchestrator` 是核心调度器，构造时注册 4 种 `ModeHandler` 并按 `graph.mode` 分派：

```java
public Orchestrator(AgentExecutor executor) {
    this.modeHandlers = new HashMap<>();
    register(new PipelineModeHandler(executor));
    register(new SupervisorModeHandler(executor));
    register(new SwarmModeHandler(executor));
    register(new DebateModeHandler(executor));
}
public void register(ModeHandler handler) { modeHandlers.put(handler.mode(), handler); }
public List<String> listModes() { return List.copyOf(modeHandlers.keySet()); }
```

`run(graph, ctx)`：为 `ctx` 生成 `executionId`（缺省时）→ 取 `graph.mode`（缺省 `PIPELINE`）→ 查 `modeHandlers` → 命中则 `handler.run(graph, ctx)`，未命中 `Flux.error(IllegalArgumentException)`。

## 7.6 四种 ModeHandler

`ModeHandler` 接口：`String mode()` / `Flux<AgentEvent> run(graph, ctx)` / `default OrchestrationResult runSync(graph, ctx)`（默认实现阻塞等待 `run` 流并聚合为 `OrchestrationResult.success/failure`）。下表为速览，随后逐个详述。

| 模式 | 标识 | 适用场景 | 控制流 |
|------|------|----------|--------|
| Pipeline | `PIPELINE` | 流程明确的标准化交付（SDLC 阶段链） | 拓扑排序，串行，产出逐级传递 |
| Supervisor | `SUPERVISOR` | 任务边界不清、需动态决策分派 | 中央 Supervisor 路由 + 子 Agent 分派 |
| Swarm | `SWARM` | 探索性、边界动态变化的任务 | Agent 间自主 handoff，无中心节点 |
| Debate | `DEBATE` | 架构选型、技术方案多视角权衡 | 并行多方案 + 裁判裁决 |

### 7.6.1 Pipeline 模式

固定顺序的串行流水线，每节点处理一个 SDLC 阶段，产出传递给下一节点。适用：需求→架构→开发→审查→测试→部署→运维。

```
 graph_start(mode=PIPELINE)
   │
   ▼
 topoOrder(graph)  ── 沿 edges 找入度为0起点，链式排序
   │
   ▼
 pipelineStep(ordered[0])  ──────────────────────────────────►
   ├─ node_start(n1, role=REQUIREMENT_PM)
   ├─ 上一节点产出 / ctx.variable("input") → AgentTask.message
   ├─ executor.executeStream(task) ── 转发 ReAct 事件 ── 累积 content
   ├─ node_complete(n1)
   ▼
 pipelineStep(ordered[1], accumulated=本节点产出)
   ├─ node_start(n2, role=ARCHITECT) ... node_complete(n2)
   ▼
 ... 直到 index >= ordered.size()
   ▼
 graph_complete(status=SUCCESS, output=accumulated)
```

### 7.6.2 Supervisor 模式

中央 Supervisor Agent 每轮输出"分派给谁 + 子任务描述"，子 Agent 执行后结果回传 Supervisor，决定继续分派或结束。适用：任务边界不清晰、需动态决策。

```
                      ┌─────────────────┐
  input ─────────────►│  Supervisor      │ (roleCode 指定中央调度角色)
                      │  LLM 路由决策     │
                      └────────┬─────────┘
            分派                  │ 回传
   ┌──────────┬──────────┬───────┴────────┐
   ▼          ▼          ▼                 ▼
 子Agent A  子Agent B  子Agent C  ...    汇总
   └──────────┴──────────┴─────────────────┘
                      │
                      ▼ 继续/结束决策 → graph_complete
```

> 简化实现按 `agentNodes` 顺序依次执行；完整的"多轮 LLM 路由决策"由使用方通过 `ReasoningKernel` SPI（见 [09 记忆与认知](09-memory-cognition.md)）在节点配置中定制增强。

### 7.6.3 Swarm 模式

轻量级 Agent 间控制权传递（handoff），无中心节点。当前 Agent 执行后自主判断"完成了/该交出去了"并 handoff。命中 FINISH 或触发防环即结束。适用：探索性、边界动态变化。

```
 swarmStep(index=0, depth=0, handoffChain={})
   ├─ 防环检查: depth>=maxHandoffs(默认8) 或 节点已访问 → handoff(FINISH_OR_LIMIT) → graph_complete
   ├─ node_start(n0, role)
   ├─ executor.executeStream(message=上一Agent输出 或 input) ── 累积 content
   ├─ node_complete(n0, depth)
   ▼ handoff → swarmStep(index+1, depth+1, input=本节点输出)
   ...
   └─ 完整路由（解析 HANDOFF/FINISH 指令）由使用方 ReasoningKernel 实现
```

防环双重保险：`handoffChain`（`Set<String>`，已访问节点）+ `maxHandoffs`（默认 `8`）。

### 7.6.4 Debate 模式

多个 Agent 持不同立场/方案辩论，由裁判 Agent 汇总最优方案。适用：架构选型、技术方案评审等高风险决策。

```
 graph_start(mode=DEBATE)
   │
   ▼ 筛选 debaters(type=AGENT) 与 judge(type=MERGE/ROUTER, 可空)
   │
   │  并行 flatMap(debaters.size())
   ├─► node_start(A, phase=DEBATE) → executeStream(topic) → 累积 proposalA → node_complete(A)
   ├─► node_start(B, phase=DEBATE) → executeStream(topic) → 累积 proposalB → node_complete(B)
   └─► node_start(C, phase=DEBATE) → ...                  → 累积 proposalC → node_complete(C)
   │
   ▼ debateFlux.collectList()
   │
   ├─ judge==null → graph_complete(status=SUCCESS, proposals=N, output=合并文本)
   ▼ judge!=null
   resolveJudge(judge):
   ├─ node_start(judge, phase=JUDGE)
   ├─ executeStream("以下是多个候选方案...方案分隔...") → 累积 verdict
   ├─ node_complete(judge, phase=JUDGE)
   └─ graph_complete(status=SUCCESS, output=verdict)
```

## 7.7 三种 AgentRuntime

AGENT 节点的执行策略由 `AgentRuntime` 决定（`Flux<AgentEvent> execute(AgentTask task)`）。`OrchestrationEngine` 的 `defaultExecutionMode`（`AgentRoleSpec`）决定选哪个运行时；`GraphNode.executionMode` 可覆盖。

| 运行时 | 策略 | 关键参数 | 适用 |
|--------|------|----------|------|
| `ReactRuntime` | 直接委托 `AgentExecutor`（Thought→Action→Observation + 工具并行） | — | 默认 |
| `PlanExecuteRuntime` | 先 LLM 规划任务列表再逐步 ReAct | — | 边界清晰但需先拆解的复杂任务 |
| `ReflexionRuntime` | ReAct 后 Critic 评估，不过则反思重做 | `maxReflectionRounds` 默认 3 | 高质量要求（精确性/正确性验证） |

```java
public ReflexionRuntime(AgentExecutor executor) { this(executor, 3); }
public ReflexionRuntime(AgentExecutor executor, int maxReflectionRounds) { ... }
```

`ReactRuntime` 即 `executor.executeStream(task)` 的薄封装。`PlanExecuteRuntime` 默认实现以单步回退到 ReAct，使用方可继承覆写规划逻辑。`ReflexionRuntime` 的 Critic 评估与反思由使用方通过 `ReasoningKernel` SPI 配合实现；未提供时回退单次 ReAct。

## 7.8 GoalPlanner 目标分解

```java
public interface GoalPlanner {
    OrchestrationGraph decompose(AutonomousGoal goal, OrchestrationContext ctx);
}
```

分解策略（三级路由，见接口 Javadoc）：① 经验库匹配 → 命中复用历史 PlanTree（微调）；② LLM Planner 规划 → 生成 PlanTree 草案；③ Critic 验证 → 不通过则回到规划（限 N 轮）。使用方提供实现完成真实分解。`DefaultGoalPlanner` 为简单回退实现：按 `goal.type` 推荐模式并生成单 AGENT 节点图。

`DefaultGoalPlanner.recommendMode(goal)` 路由表：

| goal.type | 推荐模式 |
|-----------|----------|
| `FEATURE` / `BUGFIX` | `PIPELINE` |
| `REFACTOR` / `OPS` | `SUPERVISOR` |
| `RESEARCH` | `SWARM` |
| `null` / 其他 | `PIPELINE` |

## 7.9 AutonomousExecutor 自主目标循环

给定高层目标，自动执行"分解→执行→验收→反思重做"循环，发射 `goal_start`/`goal_decomposed`/`reflection`/`goal_complete` 事件。

```
 executeGoal(goal):
   ├─ goal_start {goalId, description}
   ▼
 runWithReflection(goal, ctx, iteration=0):
   ├─ iteration >= maxIterations? ──► goal_complete(FAILED, "超出自主迭代上限")
   ├─ ctx.iteration = iteration
   ├─ goalPlanner.decompose(goal, ctx) → graph
   ├─ goal_decomposed {graphId, mode}
   ├─ orchestrator.runSync(graph, ctx) → result
   ├─ result.outputs → ctx.putVariable
   ├─ verifyGoal(goal, result, ctx)?   (默认: status==SUCCESS 即通过)
   │     ├─ 通过 ──► goal_complete(SUCCESS, iterations, output)
   │     └─ 不通过 ──► reflection {iteration, reason} ──► runWithReflection(iteration+1)
   └─ Verifier 由使用方继承覆写或提供 SPI 实现真实验收（基于 acceptances 列表）
```

### 六重防失控边界

| 边界 | 机制 | 默认 |
|------|------|------|
| 迭代上限 | `AutonomousGoal.maxIterations`，超出强制停止并请求人工介入 | 5 |
| Token 预算 | `AutonomousGoal.budgetTokens` | 可空 |
| 时间预算 | 单图执行超时 | 取决于 LLM/工具超时 |
| HITL 强制节点 | 关键阶段 `HUMAN` 节点请求审批（见 [08 HITL](08-hitl.md)） | NoOp 直接批准 |
| Reflexion 重做限制 | 同节点重做轮次，`ReflexionRuntime.maxReflectionRounds` | 3 |
| 资源配额 | 沙箱/工具调用配额（`ToolExecutor` + `SandboxExecutor`） | 框架配置 |

## 7.10 SDLC 角色体系（12 角色）

`RoleRegistry` 内置 12 个 SDLC 标准角色（`defaultSdlcRoles()`），开箱即用，使用方可注册同名角色覆盖。`AGENT` 节点按 `roleCode` 由 `RoleRegistry.resolve` 解析 `AgentRoleSpec`（`roleCode` / `roleName` / `sdlcPhase` / `systemPromptTemplate` / `toolNames` / `skillCodes` / `memoryDomain` / `defaultExecutionMode` / `capability`）。

`CapabilityCard`（`summary` / `capabilities` / `inputSchema` / `outputSchema`）供 Supervisor 路由决策。

| roleCode | roleName | sdlcPhase | memoryDomain |
|----------|---------|-----------|--------------|
| `REQUIREMENT_PM` | 需求PM | REQUIREMENT | requirement |
| `ARCHITECT` | 架构师 | DESIGN | architecture |
| `DEVELOPER` | 开发者 | DEVELOP | coding |
| `REFACTOR` | 重构工程师 | DEVELOP | refactor |
| `CODE_REVIEW` | 代码审查 | REVIEW | review |
| `SECURITY_AUDIT` | 安全审计 | REVIEW | security |
| `TEST_ENGINEER` | 测试工程师 | TEST | test |
| `QA` | QA工程师 | TEST | qa |
| `DEVOPS` | DevOps工程师 | DEPLOY | deploy |
| `SRE` | SRE工程师 | OPS | ops |
| `DATABASE_DESIGN` | 数据库设计 | DESIGN | database |
| `DOC_ENGINEER` | 文档工程师 | OPS | doc |

角色来源为"代码注册 + `RoleConfigSource` SPI"双轨：`AgentEngineAutoConfiguration` 注入外部 `RoleConfigSource.loadRoles()` 与 Spring 注入的 `AgentRoleSpec` Bean 列表，未覆盖才填充内置默认（`putIfAbsent`）。

## 7.11 构建编排图示例

```java
OrchestrationGraph graph = OrchestrationGraph.builder()
    .graphId(UUID.randomUUID().toString())
    .name("需求→架构→开发")
    .type(GraphType.SDLC_PIPELINE)
    .mode(OrchestrationMode.PIPELINE)
    .nodes(List.of(
        GraphNode.builder().nodeId("n1").type(NodeType.AGENT).refId("agent-pm")
            .roleCode("REQUIREMENT_PM").executionMode(ExecutionMode.REACT).build(),
        GraphNode.builder().nodeId("n2").type(NodeType.AGENT).refId("agent-arch")
            .roleCode("ARCHITECT").executionMode(ExecutionMode.PLAN_EXECUTE).build(),
        GraphNode.builder().nodeId("n3").type(NodeType.HUMAN).refId("approval-arch")
            .config(Map.of("type", "APPROVE_REJECT")).build(),
        GraphNode.builder().nodeId("n4").type(NodeType.AGENT).refId("agent-dev")
            .roleCode("DEVELOPER").build(),
        GraphNode.builder().nodeId("n5").type(NodeType.TOOL).refId("git_commit").build()
    ))
    .edges(List.of(
        GraphEdge.builder().fromNode("n1").toNode("n2").build(),
        GraphEdge.builder().fromNode("n2").toNode("n3").build(),
        GraphEdge.builder().fromNode("n3").toNode("n4")
            .condition("decision == 'APPROVED'").build(),
        GraphEdge.builder().fromNode("n4").toNode("n5").build()
    ))
    .variables(Map.of("input", "实现 OAuth2 登录模块"))
    .build();

OrchestrationContext ctx = OrchestrationContext.builder()
    .userId("u1").tenantId("t1").sessionId("s1")
    .variables(new HashMap<>(Map.of("input", "实现 OAuth2 登录模块")))
    .build();

Flux<AgentEvent> stream = orchestrationEngine.executeStream(graph, ctx);
```

## 7.12 自主目标示例

```java
AutonomousGoal goal = AutonomousGoal.builder()
    .goalId("g-001")
    .description("为系统实现 OAuth2 登录模块")
    .type("FEATURE")
    .constraints(List.of("必须用 Spring Security", "支持 OIDC"))
    .acceptances(List.of("登录联调通过", "安全审计无高危"))
    .domain("security")
    .maxIterations(5)
    .budgetTokens(500_000L)
    .build();

Flux<AgentEvent> goalStream = orchestrationEngine.executeGoal(goal);
// goal_start → goal_decomposed → node_* → ... → goal_complete(SUCCESS)
```

## 7.13 与其他模块的关系

- 编排层的每个 AGENT 节点最终委托 `AgentExecutor`（[03 执行引擎](03-core-engine.md)）执行 ReAct。
- HUMAN 节点通过 `HitlGateway` SPI（[08 HITL](08-hitl.md)）请求审批。
- ReflexionRuntime 的 Critic 与 AutonomousExecutor 的验收由 `ReasoningKernel` SPI 提供（[09 记忆与认知](09-memory-cognition.md)）。
- 流式事件统一为 `AgentEvent`（`com.gewu.agent.engine.core.event`），编排层扩展事件见 [13 事件协议](13-event-protocol.md)。

## 7.14 小结

编排引擎把"多个 Agent 怎么协作"抽象为可声明、可调度、可流式观测的编排图。四种模式覆盖从固定流水线到动态交接再到辩论共识的协作形态；三种运行时覆盖从单次 ReAct 到规划执行再到反思重做的推理策略；`AutonomousExecutor` 在六重边界下把"给个目标就交付"变成可控的现实能力。整套设计对标 LangGraph / AutoGen，但面向 Java + Spring Boot 生态，且与 HITL、记忆、认知 SPI 深度打通，形成自洽的 Agent 编排内核。