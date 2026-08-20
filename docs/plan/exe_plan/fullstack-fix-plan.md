# 全栈功能完整性修复计划

> **文档编号**：PLAN-FIX-GEWU-2026-08
> **版本**：V1.0
> **发布日期**：2026年8月15日
> **依据**：《格物平台全栈功能完整性审查报告》（2026-08-15，基于 codebase-memory 17,826 节点知识图谱 + 3 维度深度审查）
> **状态**：执行中

---

## 一、修复范围与目标

### 审查结论回顾

| 维度 | 完成度 | 核心问题 |
|------|--------|---------|
| 既有业务（认证/对话/需求/项目/管理） | 90%+ | 完整可用 |
| 新增编排/HITL/审计链能力 | 10-40% | 组件孤儿化 + 2 个 P0 必炸点 |
| 前端 | 60% | 19 新端点零 UI + 5 页面 Mock + 1 页面 404 |

### 修复目标

1. **消除 2 个 P0 运行时必炸点**（V28 表缺列、MCP 路径 404）
2. **打通 3 条断裂业务流**（HITL 审批创建、WORM 审计链写入、六重边界生效）
3. **接线 20 个孤儿组件中的高价值 12 个**（执行管线接入）
4. **前端补齐 MCP 404 修复**（新页面开发列为后续迭代，不在本计划）

---

## 二、修复批次（按优先级）

### 批次 P0：致命缺陷（立即执行）

#### P0-1: V28 audit_chain 表补缺失的 4 列

- **问题**：`AuditChainEntity extends BaseEntity`，但 V28 表缺 `deleted`/`updated_at`/`created_by`/`updated_by`。
  INSERT 时 `AuditMetaObjectHandler` 自动填充 4 字段 -> `Unknown column 'deleted' in 'field list'`；
  SELECT 时 `@TableLogic` 自动追加 `AND deleted=0` -> `Unknown column in 'where clause'`。
- **修复**：新建 `V30__audit_chain_fix.sql` 补 4 列（不修改已执行的 V28，遵循 Flyway 不可变原则）。
- **文件**：`gewu-interface/src/main/resources/db/migration/V30__audit_chain_fix.sql`
- **验证**：AuditChainController 的 verify/list 端点不再 SQL 报错。

#### P0-2: 前端 MCP API 路径修复

- **问题**：`gewu-web/src/lib/mcp.ts` 使用 `/v1/mcp`，后端实际为 `/api/v1/mcp-servers`，MCP 管理页 4 个操作全部 404。
- **修复**：修改 `src/lib/api.ts` 中 `MCP_SERVER` 常量为 `/v1/mcp-servers`。
- **文件**：`gewu-web/src/lib/api.ts`（1 行）
- **验证**：MCP 页面列表/创建/删除/激活可用。

### 批次 P1：断裂业务流打通

#### P1-1: ModeHandler 增加 HUMAN 节点处理（HITL 触发入口）

- **问题**：`NodeType.HUMAN` 全代码库无 handler；编排执行中永远不产生审批请求。
- **修复**：在 `PipelineModeHandler` 中增加 HUMAN 节点分支：
  遇到 HUMAN 节点 -> 构建 `ApprovalRequest` -> `hitlGateway.requestApproval()`（阻塞等待）->
  APPROVED 则继续下游，REJECTED 则终止图并标记 FAILED。
- **文件**：
  - `gewu-agent-engine/.../orchestration/mode/PipelineModeHandler.java`（修改：注入 HitlGateway + HUMAN 分支）
  - `gewu-agent-engine/.../config/AgentEngineAutoConfiguration.java`（修改：orchestrator bean 注入 HitlGateway）
- **验证**：编排图含 HUMAN 节点时，approval_request 表产生记录，审批后恢复执行。

#### P1-2: WORM 审计链接入执行管线

- **问题**：`AuditChainService.append()` 全代码库零调用方，审计链永远为空。
- **修复**：
  1. `DbAuditServiceAdapter.recordAgentExecution()` 追加调用 `auditChainService.append()`（可选注入，避免硬依赖）
  2. `FourPhasePipeline.auditPhase()` 追加 `auditChainService.append()` 写入 WORM 链
- **文件**：
  - `gewu-application/.../agent/adapter/DbAuditServiceAdapter.java`（修改）
  - `gewu-application/.../governance/FourPhasePipeline.java`（修改）
- **验证**：Agent 执行/编排执行后 audit_chain 表产生带链式哈希的记录。

#### P1-3: AutonomousExecutor 六重边界生效

- **问题**：`AutonomousExecutor` 无 BudgetController/AntiRunawayGuard，自主循环无预算保护（仅 maxIterations 1/6 边界）。
- **修复**：
  1. `AutonomousGoal` 新增 `timeBudgetMs` 字段
  2. `AutonomousExecutor` 注入 `BudgetController` + `AntiRunawayGuard`：
     - 每轮迭代前 `antiRunawayGuard.check()`（迭代/Token/时间/重试/配额五项检查）
     - 每轮执行后 `budgetController.consume()`（用 result.tokenUsed 记账）
     - 触发边界 -> goal_complete FAILED + onGoalFailure
  3. `AgentEngineAutoConfiguration.autonomousExecutor()` 补传两个依赖
- **文件**：
  - `gewu-agent-engine/.../orchestration/model/AutonomousGoal.java`（修改：+timeBudgetMs）
  - `gewu-agent-engine/.../orchestration/AutonomousExecutor.java`（修改）
  - `gewu-agent-engine/.../config/AgentEngineAutoConfiguration.java`（修改）
- **验证**：超时/超 Token 的自主目标被强制终止并触发进化钩子。

#### P1-4: ReactAgentExecutor 接线认知/统计/观测组件

- **问题**：PerceptionEngine/ComplexityRouter/AgentStatService/AgentMetricsRecorder/TraceService 均为孤儿。
- **修复**：在 `ReactAgentExecutor` 注入 5 个组件（全部可选，NoOp 安全降级）：
  1. **execute/executeStream 入口**：`perceptionEngine.perceive()` -> `complexityRouter.route()` ->
     感知意图+复杂度注入 system 消息增强（"任务等级 Lx/意图 xx/实体 x 个"）
  2. **LLM 调用后**：`traceService.recordTrace()`（每轮记录 phase=LLM_CALL）
  3. **执行完成**：`agentStatService.recordExecution()`（成功/失败计数+信任等级）+
     `agentMetricsRecorder.recordTaskSuccess/Failure()`（Prometheus 指标）
  4. **预算消耗**：`agentMetricsRecorder.recordBudgetUsage()`
- **文件**：
  - `gewu-agent-engine/.../core/ReactAgentExecutor.java`（修改：+5 依赖）
  - `gewu-agent-engine/.../config/AgentEngineAutoConfiguration.java`（修改：agentExecutor 装配）
- **注意**：AgentStatService/AgentMetricsRecorder 在 application 模块，agent-engine 不能直接依赖 ->
  通过已有 SPI 模式：AgentStatService 逻辑包装为 engine 无关接口暂缓，本轮先接 engine 内的
  PerceptionEngine/ComplexityRouter/TraceService/MetricService(engine SPI)；
  AgentMetricsRecorder 的接法：在 gewu-application 提供 DbMetricServiceAdapter 实现引擎 MetricService SPI
  （内部委托 AgentMetricsRecorder + AgentStatService）-> agent-engine 通过 MetricService SPI 调用，零反向依赖。
- **验证**：聊天/编排执行后 agent_stat 表更新、/actuator/prometheus 出现 agent.* 指标。

### 批次 P2：高价值孤儿组件接线

#### P2-1: ConflictResolver 接入 Orchestrator

- **修复**：`Orchestrator` 构造注入 `ObjectProvider<ConflictResolver>`，`runSync` 完成后对 outputs 中
  多节点同 key 冲突执行 `resolve()`（简化实现：Debate 模式已有 judge，此处接 Supervisor 汇总冲突）。
- **文件**：`gewu-agent-engine/.../orchestration/Orchestrator.java` + auto-config

#### P2-2: SemanticCache 接入 ReactAgentExecutor

- **修复**：`execute()` 入口 `semanticCache.get()` 命中直接返回（构造 LlmResponse）；
  执行完成 `semanticCache.put()`。通过新增引擎 SPI `ResponseCache`（engine 定义接口，application 用
  SemanticCache 实现适配）避免 engine 反向依赖 infrastructure。
- **文件**：engine 新增 `spi/ResponseCache.java`；application 新增 `adapter/SemanticResponseCacheAdapter.java`；
  ReactAgentExecutor 注入。

#### P2-3: ModelRouter 接入 ModelChoice 提示

- **修复**：暂不改 LLM 调用主链（风险高），仅将 ModelRouter 注册的模型特征用于
  `ComplexityRouter` 输出的 modelTier -> 日志输出建议（`log.info("建议模型: {}")`）。
  完整路由待统一 LLM 网关时实施。
- **文件**：无新文件（ComplexityRouter 增加 getter 供日志）

#### P2-4: WorkingMemoryService 接入记忆适配器

- **修复**：
  1. `WenshiMemoryStoreAdapter.store()` type="working" 分支 -> workingMemoryService.put()
  2. `WenshiMemoryRouterAdapter.inject()` 注入工作记忆摘要（会话中间态）
- **文件**：两个 adapter 修改

#### P2-5: ArbiterEngine 接入 DualLoopVerifier 外环 + ConflictResolver

- **修复**：
  1. `DualLoopVerifier` 外环改调 `arbiterEngine.arbitrate(output 修复建议 vs 原输出)`（需要把 verify 签名加 ArbiterEngine 参数，调用方 AutonomousExecutor 传入）
  2. `ConflictResolver.llm_arbitration` 分支改用 `arbiterEngine.arbitrate()` 使用返回 winnerIndex（替代 parties.get(0)）
- **文件**：DualLoopVerifier、ConflictResolver、AgentEngineAutoConfiguration（构造传参）

### 不在本轮范围（记录为后续迭代）

| 项 | 原因 |
|----|------|
| 前端编排/审批/审计链三页面 | 前端迭代工作量，单独计划 |
| Workflow 页连接编排 API | 同上 |
| 两套执行引擎收敛（聊天切 ReactAgentExecutor） | 行为变更风险高，需灰度 |
| Dashboard/Usage/Sandbox/Prototype 四 Mock 页 | 无断链，属增强 |
| PlanExecuteRuntime 真实双图实现 | PlanGraph/ExecutionGraph 已就绪，双图映射待编排引擎稳定后实施 |
| AgentMessage/HandoffParser 接入 Swarm | Swarm 现有序号传递可用，结构化消息待通信协议需求明确 |
| VersionedContext 替换 OrchestrationContext | 现可变 Map 满足当前场景，回滚需求触发时实施 |
| LlmJudge/SPC 接入评估流水线 | 需锚点用例集建设，独立迭代 |

---

## 三、执行顺序与验证门

```
批次 P0（2 项） ──> 编译+启动验证 ──> 批次 P1（4 项）──> 全量编译 ──> 批次 P2（5 项）──> mvn test 全量 ──> 执行记录入 done/
```

每批次完成条件：
- P0：AuditChain 端点可调；前端 MCP 编译通过（pnpm build 不在本轮，ts 类型检查即可）
- P1：mvn compile 通过 + E2E 集成测试不回归
- P2：mvn compile + mvn test 全绿

---

## 四、执行记录

执行过程中的每一步详细记录（修改文件、代码变更、验证结果）写入 `docs/plan/done/` 目录：
- `fix-log-P0.md` - P0 批次执行记录
- `fix-log-P1.md` - P1 批次执行记录
- `fix-log-P2.md` - P2 批次执行记录
