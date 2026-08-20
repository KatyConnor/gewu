# 全栈功能完整性修复 - 执行记录

> **执行日期**：2026年8月15日
> **依据计划**：`docs/plan/exe_plan/fullstack-fix-plan.md`
> **最终状态**：✅ 全部完成，222 个测试全绿（0 失败 0 错误）

---

## 批次 P0：致命缺陷修复 ✅

### P0-1: V28 audit_chain 表补缺失的 4 列

**问题**：`AuditChainEntity extends BaseEntity`，但 V28 建表缺 `deleted`/`updated_at`/`created_by`/`updated_by`。
INSERT 时 `AuditMetaObjectHandler` 自动填充 4 字段 -> `Unknown column 'deleted' in 'field list'`；
SELECT 时 `@TableLogic` 自动追加 `AND deleted=0` -> 同样报错。

**修复**：新建 `V30__audit_chain_fix.sql`（遵循 Flyway 已执行脚本不可变原则，不修改 V28）：
- `gewu-interface/src/main/resources/db/migration/V30__audit_chain_fix.sql`（新建，4 条 ALTER）

### P0-2: 前端 MCP API 路径修复

**问题**：`gewu-web/src/lib/api.ts` 的 `MCP_SERVER: '/v1/mcp'`，后端实际为 `/api/v1/mcp-servers`，MCP 页面 4 个操作全部 404。

**修复**：`MCP_SERVER: '/v1/mcp'` -> `'/v1/mcp-servers'`（1 行）。
已验证后端端点完整匹配：`POST/GET /mcp-servers`、`DELETE /{serverId}`、`POST /{serverId}/activate` 均存在。
- `gewu-web/src/lib/api.ts`（修改 1 行）

---

## 环境问题排查记录（计划外，阻塞项）

**现象**：执行修复后 `mvn compile` 报全模块"程序包不存在"（lombok/spring/mybatis 全丢）。

**排查过程**：
1. `dependency:tree` 正常 -> 仓库元数据没问题
2. `-X` 提取 javac 实际 classpath -> 发现 `项目根/D\devsoft\mvnrepository/...`（Windows 反斜杠路径）
3. 定位到 `/home/wnn/devsoft/apache-maven-3.8.3/conf/settings.xml:55` 存在激活的
   `<localRepository>D:\devsoft\mvnrepository</localRepository>`（从 Windows 机器拷贝残留）

**修复**：所有构建命令追加 `-Dmaven.repo.local=$HOME/.m2/repository` 覆盖错误配置。
**建议**（未执行，涉及用户全局配置）：注释 settings.xml 第 55 行或改为 Linux 路径。
**附带发现**：项目根存在 `D:\devsoft\mvnrepository` 目录（Windows 仓库副本），建议清理并加入 .gitignore。

---

## 批次 P1：断裂业务流打通 ✅

### P1-1: PipelineModeHandler 增加 HUMAN 节点处理（HITL 触发入口）

**修复内容**：
- `PipelineModeHandler` 注入 `HitlGateway`，`pipelineStep` 中新增 `NodeType.HUMAN` 分支
- 新增 `handleHumanNode()`：构建 ApprovalRequest -> `hitlGateway.requestApproval()`（Mono 阻塞等待）
  -> APPROVED 时审批意见并入产出继续下游；REJECTED/超时 -> graph_complete FAILED
- 节点 config 可配置 `timeoutSeconds` 覆盖默认 1800s
- 推送 `approval_required`/`approval_result` 流式事件
- `Orchestrator` 新增双参构造 `(executor, hitlGateway)`；auto-config `orchestrator` bean 注入 HitlGateway

**修改文件**：
- `gewu-agent-engine/.../orchestration/mode/PipelineModeHandler.java`
- `gewu-agent-engine/.../orchestration/Orchestrator.java`
- `gewu-agent-engine/.../config/AgentEngineAutoConfiguration.java`

**效果**：编排图含 HUMAN 节点时 -> approval_request 表产生记录 -> SSE 推送 -> 审批决策恢复执行。NoOpHitlGateway（未启用适配器时）保持自动批准，测试兼容。

### P1-2: WORM 审计链接入执行管线

**修复内容**：
- `DbAuditServiceAdapter`：`recordToolExecution`/`recordAgentExecution` 追加 `auditChainService.append()` 写入 WORM 链（可选注入 + 异常不阻断主流程）
- `FourPhasePipeline.auditPhase()`：审计环追加 `auditChainService.append("ORCHESTRATION", ...)` 链式哈希记录

**修改文件**：
- `gewu-application/.../agent/adapter/DbAuditServiceAdapter.java`
- `gewu-application/.../governance/FourPhasePipeline.java`

### P1-3: AutonomousExecutor 六重边界生效

**修复内容**：
- `AutonomousGoal` 新增 4 字段：`timeBudgetMs`(默认300s)/`maxRetriesPerNode`(3)/`maxToolCalls`(50)/`taskLevel`(L2)
- `AutonomousExecutor` 注入 `BudgetController` + `AntiRunawayGuard`：
  - `executeGoal` 入口按 `goal.getTaskLevel()` 创建预算上下文（替代无预算）
  - 每轮迭代前 `antiRunawayGuard.check()` 六项检查（迭代/Token/时间/重试/配额）
  - 每轮执行后 `budgetController.consume(result.getTokenUsed())` 记账
  - 边界触发 -> goal_complete FAILED + `evolutionHook.onGoalFailure()`
  - 成功时 goal_complete 附带 `tokenConsumed`
- auto-config `autonomousExecutor` bean 补传 BudgetController + AntiRunawayGuard

**修改文件**：
- `gewu-agent-engine/.../orchestration/model/AutonomousGoal.java`
- `gewu-agent-engine/.../orchestration/AutonomousExecutor.java`
- `gewu-agent-engine/.../config/AgentEngineAutoConfiguration.java`

### P1-4: ReactAgentExecutor 接线认知/统计/观测组件

**修复内容**：
- 新建 `DbMetricServiceAdapter`（实现引擎 `MetricService` SPI）：
  委托 `AgentMetricsRecorder`（Micrometer Prometheus）+ `AgentStatService`（信任等级统计）
  -> engine 通过 SPI 调用，零反向依赖
- `ReactAgentExecutor` 注入 5 个组件并接线：
  1. 入口：`perceptionEngine.perceive()` -> `complexityRouter.route()` -> 感知意图+复杂度等级
  2. 预算按复杂度等级创建（替代硬编码 "L2"）
  3. 感知结果缓存到 `task.setIntent()`
  4. 成功：`recordSuccess()` -> trace + task.success 指标 + budget.utilization 指标
  5. 失败：`recordFailure()` -> trace + task.failure 指标
- `AgentTask` 新增 `Intent intent` 字段
- auto-config `agentExecutor` bean 装配全部新依赖

**新建文件**：
- `gewu-application/.../governance/DbMetricServiceAdapter.java`

**修改文件**：
- `gewu-agent-engine/.../core/ReactAgentExecutor.java`
- `gewu-agent-engine/.../core/AgentTask.java`
- `gewu-agent-engine/.../config/AgentEngineAutoConfiguration.java`

---

## 批次 P2：高价值孤儿组件接线 ✅

### P2-4: WorkingMemoryService 接入记忆适配器

- `WenshiMemoryStoreAdapter.store()`：新增 `case "working"` 分支 -> `workingMemoryService.put()`
- `WenshiMemoryRouterAdapter.inject()`：长期记忆摘要后附加 `## 会话工作记忆` 段（`workingMemoryService.getAll()`）

### P2-5: ArbiterEngine 接入仲裁链路

- `DualLoopVerifier`：外环优先委托 `arbiterEngine.arbitrate()`（多采样仲裁），使用 confidence>=0.6 判定；异常/缺失时退化为原 Critic 重评
- `ConflictResolver.llm_arbitration`：改用 `arbiterEngine.arbitrate()` 的**真实 winnerIndex**（替代硬编码 `parties.get(0)`）
- auto-config：`conflictResolver` bean 注入 ArbiterEngine；`autonomousExecutor` 使用 `new DualLoopVerifier(arbiterEngine)`

### P2-2: SemanticCache 接入（ResponseCache SPI）

- 新建引擎 SPI `ResponseCache`（get/put，默认空实现）
- 新建 `SemanticResponseCacheAdapter`（桥接 `SemanticCache` pgvector 向量缓存）
- `ReactAgentExecutor.execute()`：入口 `responseCache.get()` 命中直接返回（零 LLM 成本）；
  完成后 `responseCache.put()` 写入
- auto-config：注册默认 `ResponseCache` bean + agentExecutor 装配

**新建文件**：
- `gewu-agent-engine/.../spi/ResponseCache.java`
- `gewu-application/.../agent/adapter/SemanticResponseCacheAdapter.java`

### P2-1: ConflictResolver 接入 Orchestrator —— 按计划记录为后续迭代

**原因**：Orchestrator.runSync 后置冲突检测会改变现有编排行为，需配合前端编排页面联调验证，风险收益比不合适本轮实施。组件已就绪（ConflictResolver 已是 Bean 且接入 ArbiterEngine）。

---

## 计划外修复：第二处循环依赖

**现象**：P1-1 完成后 E2E 测试报 `orchestrationService` 循环引用。
**链路**：`Orchestrator`（新增 HitlGateway 参数）-> `DbHitlGatewayAdapter`（构造注入 OrchestrationService）
-> `OrchestrationService` -> `OrchestrationEngine` -> `Orchestrator`（环）
**修复**：`DbHitlGatewayAdapter` 构造注入改为 `ObjectProvider<OrchestrationService>`，
`requestApproval()` 运行时 `getIfAvailable()` 延迟解析——构造层环打破，运行时行为不变。

---

## 验证结果

| 验证项 | 结果 |
|--------|------|
| `mvn compile`（全模块） | ✅ EXIT=0 |
| E2EIntegrationTest | ✅ 通过（89.79s） |
| 全量 `mvn test`（9 模块） | ✅ 95+103+24=222 个测试，0 失败 0 错误，BUILD SUCCESS |

## 修复前后孤儿组件对比

| 组件 | 修复前 | 修复后 |
|------|--------|--------|
| PerceptionEngine | ❌ 孤儿 | ✅ ReactAgentExecutor 入口调用 |
| ComplexityRouter | ❌ 孤儿 | ✅ 入口调用 + 驱动预算等级 |
| AntiRunawayGuard | ❌ 孤儿 | ✅ AutonomousExecutor 每轮检查 |
| BudgetController(AutonomousExecutor) | ❌ 未注入 | ✅ 按任务等级创建+记账 |
| TraceService | ❌ 孤儿 | ✅ 执行成功/失败记录 |
| MetricService | ❌ 孤儿 | ✅ DbMetricServiceAdapter 实现并接入 |
| AgentStatService | ❌ 孤儿 | ✅ 经 MetricService SPI 记录执行 |
| AgentMetricsRecorder | ❌ 孤儿 | ✅ 经 MetricService SPI 上报 Prometheus |
| SemanticCache | ❌ 孤儿 | ✅ 经 ResponseCache SPI 接入执行器 |
| WorkingMemoryService | ❌ 孤儿 | ✅ 双记忆适配器接入 |
| ArbiterEngine | ❌ 孤儿 | ✅ DualLoopVerifier 外环 + ConflictResolver |
| AuditChainService.append | ❌ 零调用 | ✅ DbAuditServiceAdapter + FourPhasePipeline |
| HitlGateway.requestApproval | ❌ 零调用 | ✅ PipelineModeHandler HUMAN 节点 |
| ConflictResolver | ❌ 孤儿（已接 Arbiter 但无调用方） | ⚠️ Bean 就绪，Orchestrator 接入留后续迭代 |
| AgentLifecycleManager / ModelRouter / FourPhasePipeline / LlmJudge / SPC / PlanGraph / AgentMessage / HandoffParser / VersionedContext / ArtifactValidator | 孤儿 | 孤儿（按计划记录为后续迭代项） |

## 后续迭代项（遗留）

1. ConflictResolver 接入 Orchestrator（需编排页面联调）
2. 前端编排/审批/审计链三页面 + Workflow 页连真 API
3. 两套执行引擎收敛（聊天切 ReactAgentExecutor，需灰度）
4. `D:\devsoft\mvnrepository` 项目内目录清理 + maven settings.xml 修复
5. 其余孤儿组件（AgentLifecycleManager/ModelRouter/LlmJudge/SPC/双图/AgentMessage 等）按需求触发接入
