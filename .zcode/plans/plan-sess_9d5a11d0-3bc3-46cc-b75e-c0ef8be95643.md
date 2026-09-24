# 双路线实施计划：主链路子代理派生（A）+ 编排层规划闭环（B）

## 0. 背景与前置风险

核实结论：主链路（ReactAgentExecutor）无子代理派生能力（内置工具仅 5 个、ToolExecutor 四通道无 agent 通道）；编排层 PARALLEL/MERGE 原语完整且有单测背书，但"汇总→规划→实施"不闭环（无 PLAN 节点、AGENT 节点无变量模板、默认规划器恒单步、PlanExecuteRuntime 串行且未接线）。

**前置协调项**：工作区存在未提交的配额体系改动（`git diff` 显示 11 文件 +918 行，含 ReactAgentExecutor/AgentTask/BudgetController/AgentEngineAutoConfiguration）。开工前需确认该改动已提交或由本次实施一并承载；下文所有锚点按**符号名**书写（行号会随该改动漂移）。

---

## 1. 路线 A：`spawn_subagents` 内置工具（agent-as-tool，对齐主流形态）

### A-1 设计语义
主 Agent 在 ReAct 循环中调用内置工具 `spawn_subagents`，参数为子代理清单：
```json
{"agents":[{"name":"调研API","agentId":"architect","prompt":"..."},
           {"name":"调研依赖","prompt":"..."}]}
```
每个条目派生一个**独立 ReAct 会话**（独立 messages、独立预算、独立 ToolContext），并行执行；聚合结果作为单个 tool result 回填主循环，主 Agent 据此继续用 `plan_task` 制定计划并实施。模型自主决定派几个、派谁（动态派生语义成立）。

### A-2 改动清单
| 文件 | 改动 |
|---|---|
| `core/AgentTask.java` | +`agentDepth`（int，`@Builder.Default 0`） |
| `config/AgentEngineProperties.Engine` | +内嵌类 `Subagents`：`enabled=true`、`maxPerSpawn=5`、`maxDepth=2`、`timeoutSeconds=180`、`corePoolSize=4`、`maxPoolSize=8`、`shareSessionHistory=false` |
| `core/AgentEngineConfig.java` | +`subAgentExecutor` 独立线程池（与 agentToolExecutor 隔离，防嵌套 join 死锁；队列满降级为调用线程串行执行） |
| `core/ReactAgentExecutor.java` | ① 常量 `SPAWN_TOOL_NAME`；`isBuiltinTool` 加分支；② `buildToolDefinitions`：`depth<maxDepth && enabled` 时注册工具定义（JSON Schema：agents 数组 ≤maxPerSpawn，项={name, agentId?, prompt 必填 ≤8000 字符}），depth 经 buildToolContext 传递；③ `executeBuiltinToolSync`/`executeBuiltinToolStream` 各加分支 → 新私有方法 `executeSpawnAgents(...)`（stream 版聚合输出包 Flux，逐子分支结束发进度事件） |
| `core/event/AgentEvent.java` | +可选常量 `SUBAGENT_STATUS`（`{index,name,status,durationMs,tokens}`；前端对未知事件类型现为忽略，不崩） |
| `gewu-web AIProcessTimeline.tsx`（可选） | 渲染"子代理×N 并行执行"一行；不强制 |
| 测试 | `ReactAgentExecutorSyncTest`/`StreamTest` 增补（见 A-4） |

### A-3 关键设计点
- **子任务构建**：agentId=条目指定或继承父；`sessionId = shareSessionHistory ? 父 : null`（默认隔离会话，`history=null`）；`message=prompt`；`agentDepth=父+1`；**配额字段（quotaTokenBudget/quotaBlockEnabled）继承父**——兼容进行中的配额改动。
- **并发**：`CompletableFuture.supplyAsync(() -> execute(subTask), subAgentExecutor)` 并行 + `allOf().join()`；单分支异常/超时降级为错误文本（`子代理[i] 失败: ...`），不拖垮整体。
- **预算闭环**：子任务各自走完整 `execute()`（自动获得独立 planExecution 预算、注入检测、截断自愈）；子代理因 `depth+1 ≥ maxDepth` 工具不注册，天然防递归；聚合后 `budgetController.consume(父budget, 子任务合计tokens)`，父熔断感知子消耗。
- **汇总格式**：头部统计（成功/失败数、总耗时、子 token 合计）+ 每分支 `## 子代理[i] name（agentId）\n{输出≤10KB}`；聚合文本过 `outputSanitizer` PII 脱敏。
- **安全护栏**：maxDepth=2、单次 ≤5、prompt 长度上限、子任务入口天然过 promptInjectionDetector、池满串行降级。
- **V1 明确不做**：子任务 DB 执行账本（用 metricService 打点）、子分支逐 token 流式转发（用 TOOL_RESULT 聚合）、`orchestration_node_execution` 落库。

### A-4 测试（mock LlmClient）
首轮返回 spawn 调用→子任务各自返回，验证：并行派发与结果聚合；depth=2 时不再注册工具（防递归）；单分支异常不影响整体；父预算累计子 token；超 maxPerSpawn/超长 prompt 校验拒绝；同步与流式双路径。

---

## 2. 路线 B：编排层补全（"汇总→规划→派发实施"闭环）

### B-1 `LlmGoalPlanner` 真实规划器
- 新增 `orchestration/planner/LlmGoalPlanner.java` implements `GoalPlanner`：经 `LlmClientRegistry` 调 LLM，提示词要求输出 `{steps:[{id,title,prompt,agentId?,dependsOn:[]}]}`（≤maxSteps 默认 8）；健壮解析（剥 ```json 围栏、字段校验），失败回退单步（对齐 DefaultGoalPlanner 兜底语义）。
- 装配：`AgentEngineAutoConfiguration` 中以 `@ConditionalOnProperty(name="agent.engine.planner.llm.enabled", havingValue="true")` 注册并置于现有 `@ConditionalOnMissingBean GoalPlanner`（约 :495）之前，LLM 版命中即生效、缺省回落 Default。

### B-2 `ExecutionGraph.fromPlanGraph` 波次映射（并行化）
- Kahn 分层：同一波次 n>1 步骤 → 生成 `PARALLEL` 节点 + n 条边至步骤 AGENT 节点 + `MERGE` 节点；n=1 波次直接串行链。步骤节点 refId=step.agentId（可空回退）。
- 单测验证双波次（1 并行波 + 1 串行波）生成的图结构与边计数。

### B-3 AGENT 节点 `inputs` 变量模板消费
- 把 `GraphNodeExecutor.renderTemplate`（`${var}` 渲染）抽为公共 `VariableTemplateRenderer` 供 TOOL/AGENT 共用。
- `PipelineModeHandler.executeAgentNode`：`node.inputs` 非空时逐条渲染；约定键 `message` 覆盖前驱输出，其余以 `## {key}\n{value}` 追加为参考段。**向后兼容**：inputs 为空行为不变。变量 key=节点 ID（ctx 已有），规划节点从此可分别引用各并行分支产出。

### B-4 `PLAN` 节点（闭环核心）
- `NodeType` +`PLAN`；`PipelineModeHandler` switch 加 case。
- 执行流程：输入（MERGE 汇总文本/前驱输出）→ 调 goalPlanner 产出 PlanGraph → 经 B-2 映射为子图 → **在当前 ctx 上内联执行子图**（新 Walk 复用 PARALLEL/MERGE/AGENT 全部既有能力；PLAN 嵌套深度上限 1，防递归）→ 子图最终输出作为本节点输出继续向后、写入 ctx 变量。
- 事件：发 `PLAN_CREATED`（计划 JSON 随事件，前端 PlanCard 可直接渲染）。
- 明确不做 SUBGRAPH 静态子图（PLAN 已覆盖动态场景，类型保留现状）。

### B-5 MERGE 静态计数缺陷修复（顺带）
分支因 ROUTER 未到达 MERGE 时，在 pathEnded 路径对 `mergeExpect` 相应节点 -1，消除"静默丢分支"缺陷（PipelineModeHandler Walk 构造 :106 与 :279 的不一致）。

### B-6 配置 / 文档 / 测试
- 配置：`agent.engine.planner.llm.enabled`（默认 false，灰度开启）、`agent.engine.orchestration.plan.max-steps=8`、`plan-node.max-depth=1`。
- 文档：`docs/agent-engine/07-orchestration.md` 增补 PLAN 节点与波次映射；`docs/analysis/agent-engine-design-analysis.md` 追加"§十二 主流引擎内核对比（已答复内容归档）+ §十三 能力补齐实施记录"。
- 测试：LlmGoalPlanner 解析三态（合法/带围栏/非法回退）；fromPlanGraph 波次；PipelineModeHandler PLAN 节点端到端（mock LLM + mock executor，覆盖"并行→MERGE→PLAN→串行实施"全链）；B-5 回归。

---

## 3. 实施顺序、质量门与边界

1. **顺序**：A、B 无代码交集（A 在 core 包、B 在 orchestration 包），分两个独立 PR；建议先 A 后 B（A 改动小、独立可验收；B 依赖对编排现状的灰度验证）。
2. **风险控制**：开工前与配额改动协调（提交后再动工或同分支承载）；B-1 默认关闭（`planner.llm.enabled=false`），对现有生产链路零影响；A 通过 `subagents.enabled` 开关可秒级回退。
3. **质量门**：交付前过 `code-review/RULES.md` P0/P1（L3-SEC 安全项）；过程按 AETC 留痕（audit/）；编码遵循 CODING.md Java 规则；每 PR 附 mvn test 全绿证据。
4. **明确不做（out of scope）**：SUBGRAPH 静态子图、跨进程持久化检查点、token 级上下文压缩、前端编排可视化编辑器（沿用 OrchestrationPage JSON 输入）、子任务 DB 账本、Wenshi 认知 SPI 接线（D-6 另行决策）。
