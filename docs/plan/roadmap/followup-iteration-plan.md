# 后续迭代优化计划（待优化项汇总）

> **文档编号**：ROADMAP-ITER-2026-Q3
> **版本**：V1.1
> **编制日期**：2026年8月17日（V1.1 更新：2026年8月18日）
> **状态**：🔄 第一批已执行（见下方执行进展）
> **基线**：阶段六 13 项补全 + 全栈功能完整性修复（222 测试全绿）
> **维护者**：架构组

---

## 执行进展（V1.1）

**第一批已完成（2026-08-18，执行记录：`docs/plan/done/iteration-optimize-done.md`）**：

| 条目 | 状态 |
|------|------|
| P0-1 Maven settings.xml 修复 | ✅ 随环境升级（Maven 3.9.16）消除 |
| P1-5 双图解耦（PlanGraph->ExecutionGraph） | ✅ 完成 |
| P1-6 ConflictResolver 接入 Orchestrator | ✅ 完成（conflictCheck opt-in） |
| P1-7 安全纵深五层接入主链路 | ✅ 完成（SecurityChain 统一 5 插件） |
| P2-1 AgentMessage/HandoffParser 接入 | ✅ 完成（Swarm 动态路由 + Supervisor 信封） |
| P2-2 VersionedContext 接入 | ✅ 完成（HITL 驳回回滚） |
| P2-3 AgentLifecycleManager 接入 | ✅ 完成（spawn/heartbeat/monitor/retire） |
| P2-4 ModelRouter 接入 | ✅ 完成（ModelSelector SPI，execute() 路径） |
| P2-6 conditionExpr + 信任等级 | ✅ 完成（trustLevel/successRate 门控） |
| P2-9 ArtifactValidator 接入 | ✅ 完成（config.outputSchema 契约校验） |

验证：全量 `mvn test` 222 个测试 0 失败 0 错误（与基线一致，无回归）。

**本批衍生遗留（新增待优化）**：
- executeStream() 模型路由（流式路径暂无感知/复杂度计算）
- OrchestrationService.cancelExecution 状态快照（需跨层上下文注册表）

**剩余待优化**：P0-2 引擎收敛、P1-1~P1-4 前端四页面、P2-5/7/8/10、P3 全部。

---

## 执行进展（V1.2 · 第二批）

**第二批已完成（2026-08-17~18，执行记录：`docs/plan/done/iteration-optimize-round2-done.md`）**：

| 条目 | 状态 |
|------|------|
| P0-2 双引擎收敛 | ✅ 完成（AgentExecutionEngine 委托 ReactAgentExecutor，chat 主链路获安全五层/记忆/预算/路由/缓存；stream 默认保留 wenshi 产品引擎避免联网搜索/文件输出回退，可配置切回） |
| 衍生-1 executeStream 模型路由 | ✅ 完成（planExecution 共用决策链：感知->复杂度->预算->ModelSelector，流式补齐语义缓存/输出脱敏/指标） |
| 衍生-2 cancelExecution 状态快照 | ✅ 完成（在途上下文注册表 + VersionedContext 快照写入 variables 字段） |
| P1-1 编排 CRUD 页面 | ✅ 完成（lib/orchestration.ts + OrchestrationPage：图 CRUD/激活/同步与 SSE 流式执行/生命周期/执行历史，含菜单注册） |
| P1-2 HITL 审批页 | ✅ 完成（AuditCenterPage 三标签：内容审批/HITL 审批/审计链） |
| P1-3 审计链页面 | ✅ 完成（同上第三标签：链列表/完整性校验/哈希展示） |
| P1-4 Workflow 真实 API | ✅ 完成（lib/workflow.ts + WorkflowPage 重构：CRUD/发布/归档/实例启停/终止/运行历史） |
| P2-5 PolicyService 落地 | ✅ 完成（V31 迁移 + DbPolicyServiceAdapter + PolicyController CRUD/激活/回滚，三场景种子策略） |
| P2-7 OTel 接入执行器 | ✅ 完成（TraceService SPI 扩展 Span 生命周期 + OtelTraceServiceAdapter 桥接；LLM/工具/迭代/执行根 Span；补 opentelemetry-sdk-extension-autoconfigure） |
| P2-8 LlmJudge/SPC 评测管线 | ✅ 完成（V32 迁移 + EvaluationService 采样评估 + 每小时 SPC 劣化检测 + EvaluationController API；挂接四环管线评估环） |
| P2-10 FourPhasePipeline 接入 | ✅ 完成（运算环委托引擎真实执行；评估环记指标+采样评测、治理环消费 PolicyService 记违规、审计环写 WORM 链；同步/流式编排均挂接） |
| P3-1 Dashboard 真实数据 | ✅ 完成（StatsController/dashboard + DashboardPage 真实统计与最近会话） |
| P3-2 Usage 真实数据 | ✅ 完成（StatsController/usage + UsagePage token/成本/状态分布/近期执行） |
| P3-3 Sandbox 真实数据 | ✅ 完成（lib/sandbox.ts + SandboxPage：列表/创建/启停/重启/续期/销毁） |
| P3-4 Prototype 真实数据 | ✅ 完成（原型列表改为项目 DESIGN 阶段 prototype/html 文档） |
| P3-5 Grafana Agent 看板 | ✅ 完成（agent-dashboard.json 7 面板 + provisioning 自动加载） |
| P3-6 Loki 部署补全 | ✅ 完成（compose 增 Loki+Promtail 服务 + Grafana 数据源/看板 provisioning 挂载） |

验证：后端全量 `mvn test` BUILD SUCCESS（含 E2E 集成测试）；前端 `pnpm build` 通过。

**Roadmap 25 项全部闭环**（首批 10 项 + 本批 15 项），衍生遗留 2 项已消除。

---

## 总览

本文档汇总格物平台当前已就绪但尚未完全接入主执行链路的组件、以及规划中但未启动的功能模块。所有条目以「待优化」状态排队，按优先级 P0-P3 分级，供后续迭代排期使用。

### 统计概览

| 优先级 | 数量 | 说明 |
|--------|------|------|
| P0 阻塞待办 | 2 | 环境/基础设施问题，随时可能影响开发 |
| P1 高价值 | 7 | 直接影响核心能力或用户体验，建议下一轮迭代 |
| P2 中价值 | 10 | 架构完备性提升，按需触发 |
| P3 低优先级 | 6 | 远期规划，不阻塞当前流程 |
| **合计** | **25** | — |

### 领域分布

| 领域 | 条目数 | 代表项 |
|------|--------|--------|
| 前端 UI | 7 | 编排/审批/审计链三页面 + Workflow 真 API |
| 认知引擎 | 2 | ModelRouter 模型路由生效、SPC 统计过程控制 |
| 编排引擎 | 6 | 双图解耦、Agent 通信协议、不可变状态、生命周期管理等 |
| 治理安全 | 3 | 安全纵深五层接入主链路、PolicyService 落地、渐进式权限 L3 |
| 可观测性 | 3 | OTel 追踪接入执行器、Grafana 看板、Loki 部署 |
| 工程/环境 | 4 | Maven 环境修复、引擎收敛、测试用例集、部署脚本 |

---

## P0：阻塞待办（建议立即处理）

### P0-1: Maven settings.xml 本地仓库路径修复
- **现状**：`/home/wnn/devsoft/apache-maven-3.8.3/conf/settings.xml` 第 55 行残留 Windows 路径
  `<localRepository>D:\devsoft\mvnrepository</localRepository>`，导致 Maven 默认 classpath 为空。
- **临时缓解**：所有构建命令追加 `-Dmaven.repo.local=$HOME/.m2/repository`。
- **风险**：新开发者、CI 流水线、IDE 导入都会踩坑，构建全部失败。
- **修复方案**：
  1. 注释 settings.xml 第 55 行，或改为 `${user.home}/.m2/repository`
  2. 清理项目根目录下误生成的 `D:\devsoft\mvnrepository` 目录
  3. 在 `.gitignore` 中追加对应排除规则
- **工作量**：0.5h
- **验证**：不加 `-Dmaven.repo.local` 执行 `mvn compile` 成功

### P0-2: GewuIDE 两套执行引擎收敛
- **现状**：聊天业务走 `WenshiChatService`（文石原引擎），Agent 编排走 `ReactAgentExecutor`（新认知引擎），两套重复实现、能力不一致（新引擎有感知/仲裁/缓存，旧引擎无）。
- **风险**：维护成本翻倍；能力漂移（比如语义缓存只在新引擎生效）。
- **修复方案**：
  1. 灰度切换：新增配置项 `gewu.chat.engine=legacy|react`，默认 legacy
  2. 功能对齐：补齐 ReactAgentExecutor 的流式输出 + SSE 事件格式（与 legacy 对齐）
  3. 灰度放量：先 10% → 50% → 100%，监控成功率/延迟/Token 消耗
  4. 下线旧引擎：稳定 2 周后删除 `WenshiChatService`
- **工作量**：3-5 人日
- **前置依赖**：语义缓存命中率稳定、流式输出对齐验证
- **验证**：双引擎并行 A/B 测试，成功率 diff < 1%，P95 延迟相当

---

## P1：高价值优化（建议下一轮迭代）

### P1-1: 前端编排管理页面（Orchestration CRUD）
- **现状**：后端编排 API（`/api/v1/orchestration/graphs` 等）完整，前端无对应页面，用户只能通过 Swagger 操作。
- **涉及端点**：`POST/GET/PUT/DELETE /graphs`、`POST /graphs/{id}/execute`、`GET /executions/{id}`
- **核心页面**：
  - 编排列表页（搜索/分页/启停）
  - 编排编辑器（节点拖拽 + 属性面板）
  - 执行详情页（节点状态时间线 + 日志）
- **工作量**：8-10 人日
- **依赖**：P1-2（审批页）可共用 SSE 事件通道

### P1-2: 前端审批中心（HITL 待办页）
- **现状**：HITL 审批通过 SSE 推送 `approval_required` 事件，但无前端审批界面，用户无法响应。
- **核心功能**：
  - 待办列表（按类型/优先级/剩余时间）
  - 审批详情（上下文预览 + 批准/驳回 + 意见输入）
  - 历史审批记录
- **工作量**：5-7 人日
- **涉及端点**：`GET /approvals/pending`、`POST /approvals/{id}/decision`、SSE 事件订阅

### P1-3: 前端审计链查看页面
- **现状**：WORM 审计链数据已写入 `audit_chain` 表，`AuditChainController` 提供 `list`/`verify` 端点，前端无展示页。
- **核心功能**：
  - 审计记录列表（按时间/类型/执行 ID 过滤）
  - 链式哈希可视化（上一区块 hash → 当前 hash 链路图）
  - 完整性校验结果展示
- **工作量**：3-4 人日
- **涉及端点**：`GET /audit-chain/list`、`GET /audit-chain/verify`

### P1-4: Workflow 页面连接真实编排 API
- **现状**：Workflow 页为 Mock 数据（占位），后端编排 API 已就绪。
- **修复方案**：
  1. Workflow 列表改为调用 `GET /orchestration/graphs`
  2. 新建/编辑改为调用 `POST/PUT /orchestration/graphs`
  3. 执行按钮改为调用 `POST /graphs/{id}/execute`
  4. 执行状态通过 SSE 实时刷新
- **工作量**：2-3 人日
- **依赖**：P1-1 可复用组件

### P1-5: 双图解耦（PlanGraph → ExecutionGraph 真实落地）
- **现状**：`PlanGraph`、`ExecutionGraph` 模型类已就绪，`PlanExecuteRuntime.planExecute()` 是桩方法；
  `GoalPlanner.decompose()` 直接产出 ExecutionGraph，跳过规划层。
- **价值**：人类可理解的计划 vs 机器执行的技术细节解耦，便于人工审核 + 计划复用。
- **修复方案**：
  1. `GoalPlanner.decompose()` 改为产出 `PlanGraph`（描述性步骤 + 角色编码）
  2. `ExecutionGraph.fromPlanGraph()` 真实实现策略注入（模型/工具/沙箱映射）
  3. `PlanExecuteRuntime.planExecute()` 完整实现：plan → map → execute → 回填
  4. 计划图快照存入 `orchestration_execution.plan_snapshot` 字段
- **工作量**：3-4 人日
- **风险**：影响所有编排执行路径，需完整回归测试

### P1-6: ConflictResolver 接入 Orchestrator 主链路
- **现状**：`ConflictResolver` 组件已就绪（含四策略 + ArbiterEngine 仲裁），但 `Orchestrator.runSync()` 完成后不触发冲突检测；Debate 模式用内置 judge，未走统一 ConflictResolver。
- **价值**：多 Agent 协作时输出一致性显著提升；冲突有统一处理路径。
- **修复方案**：
  1. `Orchestrator` 注入 `ObjectProvider<ConflictResolver>`
  2. 执行完成后检测同 key 多节点输出冲突，调用 `resolve()`
  3. Debate 模式 judge 改为委托 `ConflictResolver`
- **工作量**：1-2 人日
- **依赖**：P1-5（双图落地后冲突语义更清晰）

### P1-7: 安全纵深五层接入主链路
- **现状**：`SecurityChain` 已注册 `SchemaValidator`；`PromptInjectionDetector`、`OutputSanitizer` 类存在但未接入执行链路；
  `SsrfValidator`/`CodeScanner` 在 `ToolExecutor` 中硬编码调用，未走 SecurityChain。
- **价值**：统一安全插件机制，可插拔、可配置、可审计。
- **修复方案**：
  1. `SsrfValidator` 实现 `SecurityCheck` 接口，注册到 SecurityChain
  2. `CodeScanner`/`DefaultCodeScanner` 实现 `SecurityCheck` 接口，注册到 SecurityChain
  3. `ToolExecutor` 移除独立 ssrfValidator/codeScanner 字段，统一通过 SecurityChain 执行
  4. `ReactAgentExecutor` 入口调用 `securityChain.checkInput()`（PromptInjectionDetector）
  5. `ReactAgentExecutor` 出口调用 `securityChain.checkOutput()`（OutputSanitizer）
- **工作量**：2-3 人日
- **验证**：构造提示注入用例 → 被拦截；构造 PII 输出 → 被脱敏

---

## P2：中价值优化（按需触发）

### P2-1: Agent 通信协议（AgentMessage + HandoffParser）
- **现状**：`AgentMessage`（结构化消息信封）、`HandoffParser`（HANDOFF/FINISH 指令解析）类已就绪，
  但 `SwarmModeHandler` 仍用 `index+1` 顺序传递，`SupervisorModeHandler` 仍传纯文本。
- **价值**：Agent 间通信标准化，支持 HANDOFF 动态路由、结构化 artifact 传递、预算上下文透传。
- **修复方案**：
  1. `SwarmModeHandler.swarmStep()` 用 `HandoffParser.parse()` 解析当前 Agent 输出决定路由
  2. `SupervisorModeHandler.supervisorStep()` 构建 `AgentMessage` 结构化委托
  3. 所有 ModeHandler 统一转发结构化 artifact
- **工作量**：2-3 人日
- **触发条件**：多 Agent 协作场景复杂度提升，顺序传递不够用时

### P2-2: 不可变状态管理（VersionedContext）
- **现状**：`VersionedContext` 类已就绪（copy-on-write + 版本快照 + 回滚），但 `OrchestrationContext.variables`
  仍是可变 `HashMap`，`HitlGateway.rollback()` 未接入。
- **价值**：审批驳回可精确回滚到指定版本；问题排查有完整版本历史。
- **修复方案**：
  1. `OrchestrationContext` 将 `variables` 替换为 `VersionedContext`（保持 API 向后兼容）
  2. `HitlGateway.rollback()` 调用 `VersionedContext.rollback()`
  3. `OrchestrationService.cancelExecution()` 记录最终状态快照
- **工作量**：1-2 人日
- **触发条件**：HITL 驳回回滚需求出现时

### P2-3: Agent 生命周期管理（AgentLifecycleManager）
- **现状**：`AgentLifecycleManager` 类已就绪（spawn/heartbeat/monitor/terminate/retire + 死锁检测），
  但未接入 `Orchestrator` / `AutonomousExecutor`，Agent 实例无统一生命周期管理。
- **价值**：Agent 实例可观测、可回收、可诊断死锁。
- **修复方案**：
  1. `Orchestrator` 执行节点时调用 `lifecycleManager.spawn()` / `retire()`
  2. `AutonomousExecutor` 每轮迭代调用 `heartbeat()`
  3. 新增监控端点：`GET /admin/agents/active`、`GET /admin/agents/deadlocked`
- **工作量**：2 人日
- **触发条件**：并发 Agent 数 > 50 或需要资源配额管理时

### P2-4: ModelRouter 模型路由接入 LLM 调用主链
- **现状**：`ModelRouter`（含 `ModelChoice` / `complexityToTier` / `degrade` 降级）类已就绪，
  但 LLM 调用时硬编码使用 `llmConfig.getDefaultModel()`，不走动态路由。
- **价值**：按复杂度自动选模型（简单问题用小模型省钱，复杂问题用大模型保质），支持故障自动降级。
- **修复方案**：
  1. `ReactAgentExecutor` 在 `buildRequest()` 前调用 `modelRouter.route(complexityLevel)`
  2. 替换硬编码 `modelName` / `modelProvider`
  3. 失败时调用 `modelRouter.degrade()` 重试
  4. 记录模型选择指标到 Prometheus
- **工作量**：2 人日
- **前置依赖**：复杂度路由器已接入（✅ 已完成）

### P2-5: PolicyService 策略服务落地
- **现状**：`PolicyService` SPI 接口已定义（getActivePolicy / checkPolicy / rollbackPolicy），
  但仅有 NoOp 实现，无 DB 存储的策略适配器。
- **价值**：安全策略、权限策略、预算策略统一管理 + 版本化 + 可回滚。
- **修复方案**：
  1. 新建 `policy` 表 + Flyway 迁移
  2. 新建 `PolicyEntity` / `PolicyMapper`
  3. 新建 `DbPolicyServiceAdapter` 实现 `PolicyService` SPI
  4. 管理端策略 CRUD API
- **工作量**：3-4 人日
- **触发条件**：多租户场景或安全合规要求策略可追溯时

### P2-6: 渐进式权限 L3 条件表达式评估
- **现状**：`PermissionEvaluationService.conditionExpr` 未评估；`AgentStatService` 信任等级（L0-L3）已就绪，
  但权限判断未使用信任等级动态调整。
- **价值**：基于历史成功率的动态信任，高信任 Agent 自动获得更多权限，低信任 Agent 强制人工审核。
- **修复方案**：
  1. `PermissionEvaluationService` 实现 `conditionExpr` 解析（支持 `trustLevel >= L2 AND toolType == 'write'` 类表达式）
  2. `DbPermissionServiceAdapter` 权限判断时传入 `agentStatService.getTrustLevel()`
  3. 信任等级不满足要求时强制 `ask`（升级为 HITL）
- **工作量**：2 人日

### P2-7: OpenTelemetry 追踪接入执行器
- **现状**：`OrchestrationTracer` 类已就绪（startSpan/endSpan/addEvent），OTel 依赖已加入 pom，但
  `ReactAgentExecutor` / `AutonomousExecutor` 未包装 Span，实际无追踪数据上报。
- **价值**：链路追踪可观测，LLM 调用/工具执行/验证各阶段耗时可视化。
- **修复方案**：
  1. `ReactAgentExecutor` 每轮 LLM 调用 + 工具执行包装 Span
  2. `AutonomousExecutor` 每轮迭代包装 Span（含子 Span：perceive/plan/act/verify）
  3. `application.yml` 配置 OTLP endpoint（默认指向 Jaeger）
  4. 部署 `deploy/k8s/jaeger.yaml`
- **工作量**：2 人日
- **依赖**：Jaeger 部署就绪

### P2-8: LlmJudge / SPC 接入评估流水线
- **现状**：`LlmJudge`（LLM 裁判）类存在但未接入 `FourPhasePipeline.evaluatePhase()`；
  无 SPC（Statistical Process Control）统计过程控制组件。
- **价值**：输出质量量化评估 + 过程稳定性监控，可触发质量告警。
- **修复方案**：
  1. 建设锚点用例集（100+ 条，含预期输出 + 评分标准）
  2. `FourPhasePipeline.evaluatePhase()` 委托 `LlmJudge.evaluate()`
  3. 新建 `StatisticalProcessControl` 组件（Cpk/Ppk 计算 + 控制图）
  4. 指标上报 Prometheus，超控制限触发告警
- **工作量**：5-7 人日（含用例集建设）
- **触发条件**：质量稳定性要求提升，需要量化指标时

### P2-9: ArtifactValidator 合约验证接入编排输出
- **现状**：`ArtifactValidator` 已就绪（JSON Schema + 规则引擎），但编排执行输出不经过合约验证。
- **价值**：下游消费方（API、其他 Agent）收到的输出符合契约，减少集成错误。
- **修复方案**：
  1. 节点配置中增加 `outputSchema` 字段
  2. `Orchestrator` 节点执行完成后调用 `artifactValidator.validate()`
  3. 验证失败 → 标记节点 FAILED + 触发重试/降级
- **工作量**：1-2 人日

### P2-11: executionMode PLAN_EXECUTE 运行时接线（编排 O4，后续评估）

**状态**：🟡 待评估（2026-09-26 记入，源自 EXEPLAN-ORCH-2026-09 O4 可选项）
**背景**：AGENT 节点 `executionMode` 枚举（REACT/PLAN_EXECUTE/REFLEXION/TOOL_PARALLEL）存在但执行链从不消费——所有节点一律走 ReactAgentExecutor；`orchestration/runtime/` 三个运行时类为未接线的半成品。为防误导，设计器属性面板已移除该字段（48 号 D4 决策，O1 完成）。
**接线范围**（约 2 人日）：PipelineModeHandler.executeAgentNode 按节点分派 REACT（现状不动）/PLAN_EXECUTE（接入 PlanExecuteRuntime，需先审计其完成度并适配 AgentTask 接口、SSE 事件透传、失败传播/重试对齐）；REFLEXION/TOOL_PARALLEL 可接则接否则从枚举移除；行为可区分单测；属性面板恢复下拉（标注生效）。
**评估触发条件**：实际编排场景出现"单节点任务步骤多但路径确定、需压低 Agent 自主循环轮次与 Token 成本"的明确诉求时立项；无诉求则维持搁置（枚举与 runtime 类保留，不影响现有功能）。注意与 NodeType.PLAN（图级 GoalPlanner 拆解）语义重叠，评审时需一并厘清分工。

### P2-10: 四阶段治理流水线（FourPhasePipeline）接入执行器
- **现状**：`FourPhasePipeline`（评估/治理/审计/执行四阶段）已存在且审计阶段写入 WORM 链，
  但 `ReactAgentExecutor` 执行时不经过四阶段流水线。
- **价值**：所有 Agent 执行统一治理框架，安全/合规/审计策略集中管控。
- **修复方案**：
  1. `ReactAgentExecutor.execute()` 外包一层 `fourPhasePipeline.execute()`
  2. 评估阶段：LlmJudge 预评估（可选）
  3. 治理阶段：PolicyService 策略检查 + PermissionService 权限检查
  4. 审计阶段：已接入 ✅
- **工作量**：2 人日
- **前置依赖**：P2-5（PolicyService）、P2-8（LlmJudge）

---

## P3：低优先级 / 远期规划

### P3-1: Dashboard 页面 Mock 化转真
- **现状**：Dashboard 为 Mock 数据，后端指标 API 已就绪（Prometheus + actuator）。
- **工作量**：4-5 人日
- **触发条件**：客户需要可视化运营数据时

### P3-2: Usage 用量统计页面
- **现状**：Usage 页 Mock，后端 `agent_stat` 表 + `AgentStatService` 已有数据。
- **工作量**：2-3 人日

### P3-3: Sandbox 沙箱管理页面
- **现状**：Sandbox 页 Mock，后端 `CreateProjectSandboxCommand` 等 DTO 已存在，沙箱 API 待完善。
- **工作量**：5-7 人日

### P3-4: Prototype 原型生成页面
- **现状**：Prototype 页 Mock，对应后端能力尚未规划。
- **工作量**：待定（需先做需求分析）

### P3-5: Grafana Agent 业务指标看板
- **现状**：`deploy/monitoring/grafana/dashboards/agent-dashboard.json` 规划中但未实现。
- **核心指标**：成功率、验证通过率、HITL 触发率、Token 成本、P50/P95/P99 延迟
- **工作量**：1 人日
- **前置依赖**：P2-7（OTel 追踪接入）、AgentMetricsRecorder 数据稳定

### P3-6: Loki + Promtail 日志采集部署
- **现状**：`deploy/monitoring/docker-compose.monitoring.yml` 规划中但未实现。
- **工作量**：1 人日
- **触发条件**：日志量增大，ELK 方案不可行时

---

## 附录：组件就绪状态矩阵

下表汇总所有规划组件的当前状态，便于快速评估接入成本。

| 组件 | 模块 | 类存在 | SPI 定义 | 默认实现 | 真实适配器 | 接入主链路 | 状态 |
|------|------|--------|----------|----------|------------|------------|------|
| PerceptionEngine | engine/application | ✅ | ✅ | ✅ NoOp | ✅ WenshiPerceptionAdapter | ✅ ReactAgentExecutor | ✅ 完成 |
| ComplexityRouter | engine | ✅ | — | ✅ | — | ✅ ReactAgentExecutor | ✅ 完成 |
| ArbiterEngine | engine/application | ✅ | ✅ | ✅ NoOp | ✅ WenshiArbiterAdapter | ✅ DualLoopVerifier + ConflictResolver | ✅ 完成 |
| AntiRunawayGuard | engine | ✅ | — | ✅ | — | ✅ AutonomousExecutor | ✅ 完成 |
| BudgetController | engine | ✅ | — | ✅ | — | ✅ AutonomousExecutor | ✅ 完成 |
| WorkingMemoryService | application | ✅ | — | — | ✅ | ✅ 双记忆适配器 | ✅ 完成 |
| TraceService SPI | engine/application | ✅ | ✅ | ✅ NoOp | ✅ DbTraceServiceAdapter | ✅ ReactAgentExecutor | ✅ 完成 |
| MetricService SPI | engine/application | ✅ | ✅ | ✅ NoOp | ✅ DbMetricServiceAdapter | ✅ ReactAgentExecutor | ✅ 完成 |
| ResponseCache SPI | engine/application | ✅ | ✅ | ✅ NoOp | ✅ SemanticResponseCacheAdapter | ✅ ReactAgentExecutor | ✅ 完成 |
| AuditChainService | application | ✅ | — | ✅ | — | ✅ DbAuditAdapter + FourPhasePipeline | ✅ 完成 |
| HitlGateway (HUMAN节点) | engine/application | ✅ | ✅ | ✅ NoOp | ✅ DbHitlGatewayAdapter | ✅ PipelineModeHandler | ✅ 完成 |
| AgentStatService | application | ✅ | — | — | ✅ | ⚠️ 经 MetricService 间接 | 🟡 部分 |
| ConflictResolver | engine | ✅ | — | ✅ | — | ❌ Orchestrator 未接入 | 🟡 待接入 |
| VersionedContext | engine | ✅ | — | ✅ | — | ❌ OrchestrationContext 未替换 | 🟡 待接入 |
| PlanGraph / ExecutionGraph | engine | ✅ | — | ✅ | — | ❌ GoalPlanner 未产出 PlanGraph | 🟡 待接入 |
| AgentMessage / HandoffParser | engine | ✅ | — | ✅ | — | ❌ ModeHandler 未使用 | 🟡 待接入 |
| AgentLifecycleManager | engine | ✅ | — | ✅ | — | ❌ 未接入执行器 | 🟡 待接入 |
| ModelRouter | infrastructure | ✅ | — | ✅ | — | ❌ LLM 调用未路由 | 🟡 待接入 |
| PolicyService SPI | engine | ✅ | ✅ | ✅ NoOp | ❌ 无 Db 适配器 | ❌ 未接入 | 🟡 待落地 |
| PromptInjectionDetector | engine | ✅ | — | ✅ | — | ❌ 未接入输入链路 | 🟡 待接入 |
| OutputSanitizer | engine | ✅ | — | ✅ | — | ❌ 未接入输出链路 | 🟡 待接入 |
| SsrfValidator | engine | ✅ | — | ✅ | — | ⚠️ ToolExecutor 硬编码 | 🟡 待改造 |
| CodeScanner | engine | ✅ | — | ✅ | — | ⚠️ ToolExecutor 硬编码 | 🟡 待改造 |
| ArtifactValidator | engine | ✅ | — | ✅ | — | ❌ 编排输出未验证 | 🟡 待接入 |
| FourPhasePipeline | application | ✅ | — | ✅ | — | ⚠️ 仅审计接入 | 🟡 待接入 |
| LlmJudge | application | ✅ | — | ✅ | — | ❌ 评估流水线未接入 | 🟡 待接入 |
| OrchestrationTracer | infrastructure | ✅ | — | ✅ | — | ❌ 执行器未包装 Span | 🟡 待接入 |
| StatisticalProcessControl | — | ❌ | — | — | — | ❌ | 🔴 待创建 |

---

## 迭代建议路线图

```
迭代 1（最近）：  P0-1 环境修复 + P1-1~P1-4 前端四页面
迭代 2（近期）：  P1-5 双图解耦 + P1-6 冲突解决 + P1-7 安全五层
迭代 3（中期）：  P2-1 通信协议 + P2-4 模型路由 + P2-7 OTel 追踪
迭代 4（中期）：  P2-2 不可变状态 + P2-3 生命周期 + P2-6 渐进权限
迭代 5（远期）：  P2-5 策略服务 + P2-8~P2-10 评估治理 + P3 全部
```

---

> **维护说明**：本文件随每轮迭代更新，已完成项迁移至 `docs/plan/done/` 对应记录，
> 新增待优化项追加至对应优先级分区。状态图标：🟡 待优化 / 🟢 进行中 / ✅ 已完成 / ⏸️ 暂停。
