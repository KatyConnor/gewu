# 后续迭代优化 - 执行记录（第一批）

> **执行日期**：2026年8月17-18日
> **依据计划**：`docs/plan/roadmap/followup-iteration-plan.md`
> **执行范围**：P0-1 环境修复 + P1 全部 3 项（P1-5/6/7）+ P2 全部 6 项（P2-1/2/3/4/6/9）
> **最终状态**：✅ 11/11 完成，全量测试通过（详见文末验证结果）

---

## P0-1: Maven settings.xml 本地仓库路径修复 ✅

**环境变化**：旧 Maven 3.8.3（含 Windows 路径 `D:\devsoft\mvnrepository` 配置）已被替换为
Maven 3.9.16（`MVN_HOME=/home/wnn/devsoft/mvn/maven-3.9.16`），其 `settings.xml:55` 现指向
有效 Linux 路径 `/home/wnn/devsoft/mvnrepository`（2.6G 构件，验证存在）。

**验证**：不加 `-Dmaven.repo.local` 参数直接 `mvn compile` 通过（EXIT=0），问题已随环境升级消除。
**说明**：后续构建统一使用 JDK 21（`bisheng-jdk-21.0.11`）+ Maven 3.9.16。

---

## P1-7: 安全纵深五层接入主链路 ✅

**目标**：SecurityChain 统一调度所有安全检查；输入/输出安全接入聊天主链路。

**修改内容**：

1. `SsrfValidator` 实现 `SecurityCheck` 接口（`check()` 校验 config.endpoint），
   从 ToolExecutor 独立字段升级为 SecurityChain 插件
2. 新建 `CodeScannerCheck`（tool/security）：CodeScanner SPI 适配为 SecurityCheck 插件，
   toolType=code_execute 时解析参数 JSON 提取 code/language 扫描
3. `SecurityChain` 新增 `validateUri(URI)` 统一入口（HTTP 执行 + 重定向逐跳校验委托链内 SsrfValidator）
4. `ToolExecutor` 移除 ssrfValidator/codeScanner 独立字段（构造参数 12->10），
   executeViaHttp/sendWithRedirectLimit 改走 `securityChain.validateUri()`，
   executeInSandbox 扫描前移至链内
5. `PromptInjectionDetector` 新增 `checkInput(String)`：聊天入口高风险模式（角色劫持/指令注入）拦截，
   中风险（SQL/Shell 关键词）仅告警放行（避免误伤技术讨论）
6. `ReactAgentExecutor` 注入 PromptInjectionDetector + OutputSanitizer：
   - execute()/executeStream() 入口：`checkInput(task.getMessage())`
   - execute() 最终回复：`checkOutput()` PII 脱敏后再持久化/缓存/返回
7. auto-config：`codeScannerCheck` bean 注册；securityChain 自动收集全部 SecurityCheck
   （SchemaValidator / PromptInjectionDetector / OutputSanitizer / SsrfValidator / CodeScannerCheck）

**五层落地**：身份认证（已有 JWT）-> 输入安全（PromptInjectionDetector@聊天入口）->
工具安全（SecurityChain 统一五插件）-> 输出安全（OutputSanitizer PII 脱敏）-> 数据安全（已有加密）。

---

## P1-6: ConflictResolver 接入 Orchestrator 主链路 ✅

**修改内容**：
- `Orchestrator` 新增第 4 参构造 `(executor, hitlGateway, conflictResolver)` + `resolveOutputConflicts()`
- runSync() 后处理：图级变量 `conflictCheck=true` 启用 -> 收集多个 AGENT 节点产出 ->
  产出有分歧（≥2 方且非全等）时构建 semantic 冲突 -> `conflictResolver.resolve()`（60s 超时保护）->
  胜者产出成为 finalOutput，outputs 记录 `conflictResolution=method`
- 冲突解决异常不阻断编排（降级保留原输出，仅告警）
- auto-config `orchestrator` bean 注入 `ObjectProvider<ConflictResolver>`

**设计取舍**：冲突检测设为 opt-in（`conflictCheck=true`）而非全局强制，避免对既有
Pipeline 顺序产出（各节点产出不同属正常）误触发 LLM 仲裁；Debate 模式保留专用裁判节点
（用户可见 JUDGE 阶段事件），不强行替换。

---

## P1-5: 双图解耦（PlanGraph -> ExecutionGraph 真实落地）✅

**修改内容**：
1. `GoalPlanner` 接口重构：
   - 新增 `plan()`：高层目标 -> 计划图（逻辑层，人类可读步骤描述）
   - `decompose()` 改为 default 方法：计划图 -> `ExecutionGraph.fromPlanGraph()` -> 编排图
2. `DefaultGoalPlanner`：实现 plan()（单步骤计划图）；decompose() 走双图映射并补齐
   运行时语义（node inputs/config 与原实现完全兼容，输出结构不变）
3. `PlanExecuteRuntime` 真实实现（替换桩方法）：
   - 规划：GoalPlanner.plan()（无规划器/失败回退单步，等效 ReactRuntime）
   - 映射：ExecutionGraph.fromPlanGraph()
   - 执行：Kahn 拓扑排序（环/遗漏按声明顺序补齐），逐步骤委托 executor.executeStream，
     前置产出（截断 2000 字/步）注入后续步骤上下文，事件透传补 nodeId
   - 新增 `(executor, goalPlanner)` 双参构造，旧构造保持兼容

---

## P2-2: VersionedContext 接入 OrchestrationContext ✅

**修改内容**：
- `OrchestrationContext` 图变量由 `VersionedContext` 版本化管理（懒初始化，volatile + 双检锁）：
  - `putVariable()` copy-on-write 生成新版本（返回版本号）
  - 新增 `getStateVersion()` / `rollbackTo(version)` / `rollbackOneVersion()` / `snapshotVariables()`
  - `getVariables()` 返回当前版本视图（向后兼容直接 Map 读取）
  - `setVariables()` 重置版本化状态（版本归零）
- `PipelineModeHandler` HUMAN 节点驳回分支：`rollbackOneVersion()` 撤销最近一次版本化写入 +
  graph_complete 事件携带 stateVersion/stateKeys 状态留痕

**跳过项说明**：`OrchestrationService.cancelExecution()` 状态快照未实施 - 取消入口在
application 层，不持有引擎侧 OrchestrationContext（上下文生命周期在引擎执行期间），
需要跨层上下文注册表支撑，收益不匹配改动风险，留待执行上下文持久化需求出现时实施。

**编译问题记录**：Lombok 版本不支持 `@Builder.Exclude`（"找不到符号 Exclude"），
改为普通 transient 字段 + 恢复 @AllArgsConstructor（无外部全参调用方，安全）。

---

## P2-1: AgentMessage/HandoffParser 接入 Swarm/Supervisor ✅

**修改内容**：
1. `SwarmModeHandler` 重写路由：
   - 节点完成后 `HandoffParser.parse(accumulated)` 解析输出指令
   - `FINISH[:reason]` -> AgentMessage.finish 信封 + handoff 事件 + 立即结束
   - `HANDOFF:target|reason` -> 按 nodeId/refId 定位目标节点跳转（目标不存在回退顺序传递），
     AgentMessage.handoff 信封 + handoff 事件（携带 messageId/messageType）
   - 无指令 -> 按声明顺序传递（原行为）
2. `SupervisorModeHandler` 结构化委托：
   - 专家节点执行前发 `AgentMessage.delegate()`（TASK_DELEGATE）+ `message` 事件
     （metadata: messageId/messageType/fromAgent/toAgent）
   - 专家完成后发 `AgentMessage.result()`（TASK_RESULT）+ `message` 事件
   - **修复既有 bug**：原实现 node 完成后未累积产出（accumulated 恒空，下游节点拿不到
     上游输出），现按节点累积并以 `[nodeId] 产出` 格式注入后续委托上下文

---

## P2-3: AgentLifecycleManager 接入执行器 ✅

**修改内容**：
- `AutonomousExecutor` 注入 AgentLifecycleManager（第 9 个依赖）：
  - executeGoal 入口 `spawn("autonomous", null, goalId)` 注册实例
  - runWithReflection 每轮迭代 `heartbeat()` + `monitor(sink)`（心跳超时/全局超时/死锁检测，
    问题实例自动推送 `agent_timeout`/`agent_deadlock` 流式事件）
  - 防失控边界触发 -> `terminate()`；目标达成 -> `retire()`（记录 tokenConsumed）
- auto-config：`agentLifecycleManager` bean 已存在（@ConditionalOnMissingBean），
  `autonomousExecutor` bean 补传该依赖

---

## P2-9: ArtifactValidator 接入编排输出 ✅

**修改内容**：
- `Orchestrator` 新增第 4 参构造含 ArtifactValidator + `validateNodeOutputs()`：
  - 节点 config 配置 `outputSchema`（支持字段 List / Schema Map / 逗号分隔字符串）时启用
  - 结构化产出（JSON 对象）按契约校验必需字段，纯文本产出跳过（valueToTree 文本节点无字段语义）
  - 校验失败 -> result 标记 FAILED + errorMessage（上层自主执行器反思循环触发重试）
- runSync 后处理顺序：契约校验 -> 冲突解决
- auto-config `orchestrator` bean 注入 `ObjectProvider<ArtifactValidator>`

---

## P2-4: ModelRouter 接入 LLM 调用链 ✅

**模块依赖约束**：ModelRouter 在 infrastructure（依赖 engine），不能反向 import。
采用 SPI 桥接模式（与 ResponseCache/MetricService 一致）。

**修改内容**：
1. engine 新建 `ModelSelector` SPI（select(taskDesc, complexity, privacy, latency, budgetRemaining)
   -> ModelSelection{provider, model, reason}）+ `NoOpModelSelector` 默认实现（返回 null 不干预）
2. `ModelRouter`（infrastructure）新增 `features(modelId)` 公开查询（解析 providerCode）
3. application 新建：
   - `ModelRoutingConfiguration`：注册 ModelRouter bean（`gewu.llm.routing.*` 配置驱动
     模型特征注册表：model-id/provider-code/tier/cost/latency/local）
   - `ModelSelectorAdapter`（@ConditionalOnProperty agent.engine.adapter.enabled）：
     桥接 SPI 与 ModelRouter，路由失败返回 null 回退默认模型
4. `ReactAgentExecutor` 注入 ModelSelector + `routeModelIfApplicable()`：
   - execute() 中预算创建后调用；**调用方显式指定模型（AgentTask）时尊重调用方不路由**
   - complexity 评分（1-10）+ 剩余预算传入；provider 缺省保持原值
   - 路由失败静默保持原模型
5. auto-config：NoOpModelSelector 默认 bean + agentExecutor 装配

**说明**：executeStream() 流式路径暂未接入模型路由（该路径无感知/复杂度计算），
待流式路径补齐感知引擎后统一接入。

---

## P2-6: conditionExpr 评估 + 信任等级接入权限 ✅

**修改内容**：
- `PermissionEvaluationService` 增强（渐进式权限 L0-L3 落地）：
  - 注入 `ObjectProvider<AgentStatService>`（懒解析，异常兜底 L0/0.0）
  - 匹配最高优先级权限规则后评估 `conditionExpr`：
    - `trustLevel >= L2` / `trustLevel > L1`（L0<L1<L2<L3 序数比较）
    - `successRate >= 0.8`（0-1 区间）
    - `AND` 组合多条子句
  - **条件不满足 -> 降级 ask（强制人工确认）**，reason 说明信任条件
  - 无法解析的子句默认通过（保持兼容）+ 告警日志
- 低信任 Agent 无法自动获得高权限规则授权，形成"成功率积累 -> 信任提升 -> 权限放开"渐进闭环

---

## 新建/修改文件清单

### 新建（7 个）
| 文件 | 模块 | 说明 |
|------|------|------|
| `tool/security/CodeScannerCheck.java` | agent-engine | CodeScanner 插件化适配 |
| `spi/ModelSelector.java` | agent-engine | 模型选择 SPI |
| `spi/defaults/NoOpModelSelector.java` | agent-engine | 默认空实现 |
| `agent/adapter/ModelSelectorAdapter.java` | application | ModelRouter 桥接 |
| `agent/adapter/ModelRoutingConfiguration.java` | application | ModelRouter bean + 配置 |

### 修改（16 个）
| 文件 | 改动 |
|------|------|
| `SsrfValidator.java` | implements SecurityCheck |
| `SecurityChain.java` | +validateUri(URI) |
| `PromptInjectionDetector.java` | +checkInput(String) |
| `ToolExecutor.java` | 移除 2 字段，统一 SecurityChain |
| `ReactAgentExecutor.java` | +输入/输出安全 +模型路由 |
| `AgentEngineAutoConfiguration.java` | 5 处 bean 装配更新 |
| `Orchestrator.java` | +冲突解决 +契约校验（4 参构造） |
| `GoalPlanner.java` | +plan() default decompose() 双图 |
| `DefaultGoalPlanner.java` | 双图映射实现 |
| `PlanExecuteRuntime.java` | 真实双图执行实现 |
| `OrchestrationContext.java` | VersionedContext 版本化 |
| `PipelineModeHandler.java` | 驳回回滚 + 快照留痕 |
| `SwarmModeHandler.java` | HandoffParser 动态路由 |
| `SupervisorModeHandler.java` | AgentMessage 信封 + 累积 bug 修复 |
| `AutonomousExecutor.java` | 生命周期接入 |
| `ModelRouter.java` | +features(modelId) |
| `PermissionEvaluationService.java` | conditionExpr + 信任等级 |

---

## 验证结果

| 验证项 | 结果 |
|--------|------|
| `mvn compile`（全模块） | ✅ EXIT=0 |
| 分批编译（engine/infrastructure/application） | ✅ 每批通过 |
| 全量 `mvn test` | ✅ 222 个测试，0 失败 0 错误，BUILD SUCCESS |

## 修复前后孤儿组件对比（本批）

| 组件 | 修复前 | 修复后 |
|------|--------|--------|
| PromptInjectionDetector | ❌ 未接入 | ✅ 聊天入口 + SecurityChain |
| OutputSanitizer | ❌ 未接入 | ✅ 最终回复 PII 脱敏 + SecurityChain |
| SsrfValidator | ⚠️ 硬编码字段 | ✅ SecurityChain 插件 + validateUri 统一 |
| CodeScanner | ⚠️ 硬编码字段 | ✅ CodeScannerCheck 插件 |
| ConflictResolver | ❌ 无调用方 | ✅ Orchestrator 后处理（conflictCheck） |
| PlanGraph/ExecutionGraph | ❌ 无消费方 | ✅ GoalPlanner.plan + PlanExecuteRuntime |
| HandoffParser | ❌ 无消费方 | ✅ Swarm 动态路由 |
| AgentMessage | ❌ 无消费方 | ✅ Swarm/Supervisor 结构化信封 |
| VersionedContext | ❌ 无消费方 | ✅ OrchestrationContext 版本化 + HITL 回滚 |
| AgentLifecycleManager | ❌ 无消费方 | ✅ AutonomousExecutor 全周期 |
| ArtifactValidator | ❌ 无消费方 | ✅ Orchestrator 输出契约校验 |
| ModelRouter | ❌ 无消费方 | ✅ ModelSelector SPI 接入 execute() |
| conditionExpr | ❌ 未评估 | ✅ trustLevel/successRate 门控 |

## 遗留（更新至 followup-iteration-plan.md）

1. executeStream() 模型路由（待流式路径补感知引擎）
2. OrchestrationService.cancelExecution 状态快照（需跨层上下文注册表）
3. 前端页面批次（P1-1~P1-4）与 P3 项不变
