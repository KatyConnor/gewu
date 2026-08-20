# 第二轮迭代优化执行记录（Roadmap 剩余 17 项）

- **执行日期**：2026-08-17 ~ 2026-08-18
- **计划来源**：`docs/plan/roadmap/followup-iteration-plan.md` V1.2 第二批（P0-2 + P1-1~4 + P2-5/7/8/10 + P3-1~6 + 衍生遗留 2 项）
- **构建环境**：JDK 21（bisheng-jdk-21.0.11）+ Maven 3.9.16（`/home/wnn/devsoft/mvn/maven-3.9.16`）；前端 Node 20.17.0 + pnpm 9.12.0

## 验证结论

| 验证项 | 结果 |
|--------|------|
| 后端全量 `mvn test`（common/domain/infrastructure/agent-engine/application/interface） | ✅ BUILD SUCCESS（含 PerformanceBenchmarkTest 128s、E2EIntegrationTest） |
| 前端 `pnpm build`（Next.js 14.2.35） | ✅ EXIT=0，类型检查通过 |
| Grafana 看板 JSON / compose YAML 语法 | ✅ 校验通过 |

与首批基线（222 测试 0 失败）一致，无回归。

## 工作包一：执行链完整性与双引擎收敛

### 1.1 executeStream() 模型路由补齐（衍生遗留-1）
- `ReactAgentExecutor`：提取 `planExecution()` 共用决策链（感知 -> 复杂度路由 -> 按复杂度预算 -> ModelSelector 模型路由），`execute()`/`executeStream()` 共用，两条路径产出一致 provider/model 决策
- 流式路径新增：语义缓存命中回放（CONTENT+DONE 事件）、输出 PII 脱敏后再存经验、`recordSuccess`/`recordFailure` 指标
- 预算从硬编码 `"L2"` 改为按复杂度等级创建

### 1.2 cancelExecution 状态快照（衍生遗留-2）
- `OrchestrationService`：新增在途上下文注册表 `liveContexts`（ConcurrentHashMap），执行开始注册、终态/取消移除
- `cancelExecution`/流式 `doOnCancel`：取消前从 `VersionedContext` 提取 `stateVersion + variables 终态 + currentNodeId` 序列化写入 execution 记录 `variables` 字段（无需新列）

### 1.3 P0-2 双引擎收敛
- **实现方式与计划偏差说明**：`AgentExecutionEngine`（application 模块内与 agent-engine 重复的 ReAct 循环，无安全/记忆/预算/路由能力）重写为 ReactAgentExecutor 的委托适配器，对外契约（infrastructure LlmResponse / AgentChunk）不变
- chat 主链路（默认 routing=legacy）由此收敛到 ReactAgentExecutor 全链路：安全纵深五层、记忆注入、预算熔断、感知与模型路由、语义缓存、执行统计
- **stream 路径保留默认 wenshi**：`WenshiReasoningEngine` 是带联网搜索/文件输出/结果验证的独立产品引擎（前端 ChatPage 依赖其 webSearch/verify/file 事件），切换会造成功能回退，故未按计划原文翻转默认值；如需切换仅需改 `gewu.wenshi.routing.stream: legacy`

## 工作包二：治理与评测管线闭环

### 2.1 P2-5 PolicyService 落地
- 新建 `V31__governance_policy.sql`（governance_policy 表 + 三场景种子策略：model_routing/tool_permission/hitl_threshold）
- 新建 `GovernancePolicyEntity`（domain/governance）+ `GovernancePolicyMapper`（infrastructure）
- 新建 `DbPolicyServiceAdapter`：实现 PolicyService SPI（getActivePolicy/checkPolicy/rollbackPolicy），活跃策略缓存 + 写后失效；场景版本化 CRUD 与互斥激活
- SPI `PolicyService` 新增 `rollbackPolicy` 默认方法（向后兼容）
- 新建 `PolicyController`（/api/v1/policies：版本列表/活跃策略/创建/激活/回滚/校验）

### 2.2 P2-10 FourPhasePipeline 接入执行器
- 重写 `FourPhasePipeline`：运算环 `executeGraph()` 委托 OrchestrationEngine 真实执行（原为桩）；新增 `postProcess()` 挂接入口
- 评估环：MetricService 记录 orchestration.duration/success/tokens + LlmJudge 采样评估
- 治理环：失败执行快照三场景策略 + 违规轨迹写 WORM 审计链（GOVERNANCE_VIOLATION 事件）
- 审计环：AuditLogService + AuditChainService（原逻辑保留）
- `OrchestrationService` 同步执行与流式完成/失败路径均挂接四环

### 2.3 P2-8 LlmJudge/SPC 评测管线
- 新建 `V32__evaluation_record.sql`（evaluation_record 表）
- 新建 `EvaluationRecordEntity` + `EvaluationRecordMapper`
- 新建 `EvaluationService`：`evaluateExecutionSampled()`（采样率 `gewu.evaluation.sample-rate`，默认 0.2）由四环评估环调用；默认锚点用例（准确性/完整性/安全性三维度）；结果持久化 + `agent.verification.score` 指标 + SPC 记录
- `@Scheduled` 每小时 SPC 劣化检测：degraded 时告警 + `agent.evaluation.degraded` 指标
- 新建 `EvaluationController`（/api/v1/evaluations：记录查询/劣化报告/手动评估）

## 工作包三：可观测性三层补全

### 3.1 P2-7 OTel 接入执行器
- SPI `TraceService` 新增 Span 生命周期默认方法（startSpan/endSpan/endSpanWithError，Object 句柄解耦）
- 新建 `OtelTraceServiceAdapter`（application/governance）：桥接 OrchestrationTracer（Micrometer OTel Bridge），替换 AutoConfiguration stub
- 仪表化：`ReactAgentExecutor` 同步每轮 LLM 调用 + 每次工具执行包装 Span、流式 LLM 调用订阅期 Span；`AutonomousExecutor` 每次迭代 Span（traceService 注入 AutoConfiguration 装配）；`OrchestrationService` 编排执行根 Span
- `gewu-infrastructure/pom.xml` 补 `opentelemetry-sdk-extension-autoconfigure`（OTLP 导出装配）
- application.yml 已有 `management.tracing/otlp.tracing` 配置（endpoint 默认 localhost:4317）

### 3.2 P3-5 Grafana Agent 业务看板
- 新建 `deploy/monitoring/grafana/dashboards/agent-dashboard.json`：任务成功率/验证评分趋势/HITL 触发/预算利用率/任务时长 p50p95/Token 消耗/劣化告警 7 面板
- 新建 `deploy/monitoring/grafana/provisioning/dashboards.yml`：看板 file provider 自动加载

### 3.3 P3-6 Loki 部署补全
- `docker-compose.monitoring.yml` 新增 loki（grafana/loki:2.9.0，3100）与 promtail（2.9.0，挂载既有 promtail-config.yml 推送 loki:3100）服务及数据卷
- Grafana 挂载 datasources/dashboards provisioning（Loki 数据源早已配置但此前未挂载生效）

## 工作包四：前端功能页面（8 项）

| 条目 | 交付物 |
|------|--------|
| P1-4 Workflow 真实 API | `lib/workflow.ts`（定义+实例全套 API）；`WorkflowPage` 重构：mock 数组删除，列表/创建/发布/归档/删除/实例启停/终止/运行历史全真实数据；画布保留本地编辑，返回刷新 |
| P1-1 编排 CRUD 页面 | `lib/orchestration.ts`（含 SSE 流式执行 fetch 解析）；`OrchestrationPage`：图列表/创建（模式+图定义 JSON）/激活/同步执行/SSE 事件面板/暂停恢复取消/执行历史；`PageType` 增加 'orchestration'，Sidebar 菜单+路由注册 |
| P1-2 HITL 审批页 | `lib/audit.ts` 扩展 approvals API；AuditCenterPage 改造三标签页（内容审批/HITL 审批/审计链），HITL 批准后编排自动恢复 |
| P1-3 审计链页面 | 同页第三标签：链记录列表/完整性校验（verify）/前后哈希展示/决策轨迹 |
| P3-1 Dashboard | 后端 `StatsService.dashboard()`（agent_stat 聚合+信任分布+执行统计+最近会话）+ `StatsController`；`DashboardPage` 四卡真实数据 + 最近会话 + 执行概览 |
| P3-2 Usage | `StatsService.usage()`（token/成本/状态分布/近期执行）；`UsagePage` 重构真实数据 |
| P3-3 Sandbox | `lib/sandbox.ts`（/v1/sandboxes 全套）；`SandboxPage` 重构：列表/创建/启停/重启/续期/销毁 |
| P3-4 Prototype | `PrototypePage` 原型列表改为项目 DESIGN 阶段 prototype/html 类型文档（listMyProjects + listPhaseDocuments 聚合），编辑器/对话保持原交互 |

## 文件清单

### 新建（后端 14 + 前端 4 + 配置 4 = 22）
- SQL：`V31__governance_policy.sql`、`V32__evaluation_record.sql`
- domain：`governance/GovernancePolicyEntity.java`、`evaluation/EvaluationRecordEntity.java`
- infrastructure：`mapper/GovernancePolicyMapper.java`、`mapper/EvaluationRecordMapper.java`
- application：`governance/DbPolicyServiceAdapter.java`、`governance/OtelTraceServiceAdapter.java`、`evaluation/EvaluationService.java`、`stats/StatsService.java`
- interface：`controller/PolicyController.java`、`controller/EvaluationController.java`、`controller/StatsController.java`
- 前端：`lib/workflow.ts`、`lib/orchestration.ts`、`lib/stats.ts`、`lib/sandbox.ts`、`components/pages/OrchestrationPage.tsx`
- 部署：`grafana/dashboards/agent-dashboard.json`、`grafana/provisioning/dashboards.yml`

### 修改（后端 10 + 前端 9 + 配置 2 = 21）
- agent-engine：`ReactAgentExecutor.java`（共用决策链+Span+缓存/脱敏/指标）、`AutonomousExecutor.java`（迭代 Span）、`AgentEngineAutoConfiguration.java`（装配 traceService）、`spi/TraceService.java`、`spi/PolicyService.java`
- application：`AgentExecutionEngine.java`（重写为委托适配器）、`OrchestrationService.java`（四环挂接+根 Span+取消快照+上下文注册表）、`FourPhasePipeline.java`（重写真实四环）、`lib/audit.ts`
- 前端：`WorkflowPage`、`OrchestrationPage`（新）、`AuditCenterPage`、`DashboardPage`、`UsagePage`、`SandboxPage`、`PrototypePage`、`app/page.tsx`、`layout/Sidebar.tsx`、`types/index.ts`
- 配置：`gewu-infrastructure/pom.xml`（OTel SDK autoconfigure）、`docker-compose.monitoring.yml`（Loki+Promtail+provisioning 挂载）

## 后续建议（非本轮范围）
- chatViaWenshi 同步路径可补充 toolCalls/usage 透传（当前 wenshi 路径仅返回 content）
- 编排图编辑器当前为 JSON 输入，可演进为可视化画布（复用 WorkflowCanvas）
- Grafana 看板指标名依赖 Micrometer 命名规约（下划线转换），上线前用 /actuator/prometheus 实测核对一次
- EvaluationService 采样评估依赖评审模型可用（judge-provider 默认 deepseek），不可用时降级为"评估失败"记录不阻塞主链路
