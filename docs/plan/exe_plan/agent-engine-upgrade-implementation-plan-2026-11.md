# Agent 引擎升级实施计划（2026-11）

> **计划编号**：AE-PLAN-2026-11 | **版本**：V1.0 | **编制日期**：2026-11-11 | **状态**：🟡 待 GATE 批准后开工
> **输入链**：`docs/analysis/agent-engine-design-analysis-2026-11.md` §七/§八（已经 THEMIS T1 独立评审 `.standards/themis/records/RPT-002.md`，有条件通过）→ CAPA 整改（`CAPA-002.md`，C1-C6 已回写报告）→ 本计划为 CAPA 落地载体
> **编号纪律**：全计划采用 **AE- 前缀命名空间**（roadmap `followup-iteration-plan.md` 已占用 P0-x/P1-x/P2-x，同名异义，RPT-002 F-05）；行号锚点基准 `8234a71`（2026-11-11）
> **决策纪律**：行为级变更沿 43 号预承诺模式——先立规则与回滚，后拿数据执行；本计划所有阶段 1/3 工作项为行为保持或可配置回退，阶段 2 全部数据驱动

---

## 0. 范围与排除项

**做**：引擎可靠性清偿（P0）、数据驱动能力接线（D-5/D-6/模型路由）、工程质量治理（P2）、立项级演进准备。
**不做（本计划显式排除）**：①单轨统一（工作流并入编排图）的实施——阶段 4 仅再决策；②Firecracker microVM 实现——阶段 3 仅做"投入或移除"决策；③wenshi 引擎重启开发——仅复评；④任何 `agent.engine` SPI 协议破坏性变更（四条不变量约束）。

## 1. 编号对账表（唯一映射正本）

| 本计划 | 报告 §七 | 外部正本 | 载体性质 |
|---|---|---|---|
| AE-P0-1 | P0-1 | 49号 L1（F-03/F-08）+ RPT-002 F-02 | **AEGIS-RC 缺陷修复**（开工走 AEGIS 三单流程） |
| AE-P0-2 | P0-2 | 49号 L2（F-04 余项） | 缺陷修复（AEGIS-RC） |
| AE-P0-3 | P0-3 | 43号 §三.1 | 前置改造 |
| AE-P0-4 | P0-4 | 29号:1037-1038 安全红线 vs 10-spi-reference:115 | 安全加固 |
| AE-P0-5 | P0-5（条件性） | — | 条件触发（多副本决策） |
| AE-P1-1 | P1-1 | 43号 D-6 + **roadmap P2-11（对账，阶段 0 勘定）** | 能力接线 |
| AE-P1-2 | P1-2 | 45号 结论 1 | 能力接线 |
| AE-P1-3 | P1-3 | 45号 复评触发器 | 工程基建 |
| AE-P1-4 | P1-4 | — | 决策+实施 |
| AE-P1-5 | P1-5 | — | 内容资产 |
| AE-P1-6 | P1-6 | 49号 L3（F-10） | 改造 |
| AE-P1-7 | P1-7 | 49号 L4（F-12） | 测试补齐 |
| AE-P1-8 | P1-8 | 43号 D-5（接线分支） | 能力接线 |
| AE-P2-1~P2-8 | P2-1~P2-8 | — / 45号 §四（P2-8） | 工程质量 |

## 2. 阶段 0：立项勘定（0.5 人日，无代码变更）

| 项 | 内容 | 产出 |
|---|---|---|
| 勘定-1 | **PLAN_EXECUTE 三路径分工**：execute 级（43 号 D-6，runtimeMode 分派）/ 节点级（roadmap P2-11，GraphNode.executionMode 分派）/ 图级（NodeType.PLAN）——三者是否并存、正本归属、P2-11 状态（🟡待评估）处置（并入/维持搁置） | 决策记录（append 至本计划 §2 或新 DEC 文档）；roadmap P2-11 行更新互链 |
| 勘定-2 | AE-P0-1 / AE-P0-2 转 AEGIS-RC 立单（三单：问题单/护栏单/验证单） | `.aegis/` 单据 |

### 勘定结论（DEC-AE-PLAN-2026-11-01，2026-11-12 执行时落档）

**PLAN_EXECUTE 三路径分工勘定**——三者语义不同、并存不合并，互不替代：

| 路径 | 分派粒度 | 服务对象 | 处置 |
|---|---|---|---|
| **execute 级**（43 号 D-6：`planExecution` 产出 `systemChoice.runtimeMode` → execute/executeStream 分派） | 任务级（认知路由产出） | **chat 主对话链路**的 System2 接线 | **AE-P1-1 正本**（阶段 2，复评达标后） |
| **节点级**（roadmap P2-11：`GraphNode.executionMode` 枚举 → 节点分派） | 节点级（图定义显式配置） | 编排图内单节点运行时定制 | **维持 🟡 待评估**——触发条件（编排场景明确诉求）与 AE-P1-1 不同，不合并不替代；P2-11 行已更新互链 |
| **图级**（`NodeType.PLAN`：GoalPlanner 规划→波次子图内联执行） | 图结构级（动态规划） | "汇总→规划→派发"闭环 | **已落地**（7.15.3），无接线动作；其子 Walk 异常收尾缺陷由 AE-P0-1 清偿 |

正本声明：agent 引擎升级唯一执行正本 = 本计划；roadmap P2-11 保留其既有触发条件，两者经本节互链。

## 3. 阶段 1：可靠性清偿（≈6.5 人日，一个迭代）

| 工作项 | 目标 | 前置 | 改动点 | 验收标准 | 回滚 | 工作量 |
|---|---|---|---|---|---|---|
| **AE-P0-1**（AEGIS 首单） | 子 Walk 三路异常收尾不触碰共享 sink/executionId | 阶段 0 立单 | `PipelineModeHandler.java`：failGraph(:1021-1030)/pauseHere(:985-1003)/cancelHere(:1006-1019) 补 completionCallback 委托守卫（子 Walk 判定：`walk.graph != rootGraph` 或 ctx 父链标记）；:1041 sink.complete 仅根 Walk 可达 | ①新增 2 回归用例：fail-fast 子图失败→父图按 continueOnFailure 语义处置且事件流存活；子图内暂停→检查点存父链状态、resume 成功 ②既有 SUBGRAPH/PLAN 测试全绿 ③全量 mvn test 无回归 | 三路守卫为增量分支，revert 单 commit | 1.5 人日 |
| **AE-P0-2** | Webhook 匿名端点限流 + 异步执行 | 无（webhook.enabled 开启前必须） | `OrchestrationController.triggerByWebhook`：①限流（固定窗口/token 桶，配置 `gewu.orchestration.webhook.rate-limit.*`，默认 10 次/分钟/token）②异步化：先落 PENDING 即返 202，后台 worker 消费执行 | ①压测：同 token 11 次/分钟→第 11 次 429 ②重复触发不阻塞响应线程（响应 P99 < 100ms）③执行结果经执行历史可查 | 配置开关降级为同步直执行（旧行为） | 1.5 人日 |
| **AE-P0-3** | ConfidenceGate 全参重载替换简化重载 | 无（D-6 接线前置） | `ReactAgentExecutor.planExecution` 调用点改传真实值：toolSuccessRate（BudgetContext/执行统计）、historicalSimilarity（MemoryRouter）、contractPassRate（ArtifactValidator）；简化重载标 `@Deprecated` 并保留 | ①单测：四级门控分支全覆盖，**含 ESCALATE_HITL 可达性断言**（0.4c+0.5 下限缺陷消除）②无 Critic 场景行为不回退 | 调用点还原简化重载（@Deprecated 保留即回退开关） | 1 人日 |
| **AE-P0-4** | HITL fail-closed 高危白名单 | 无 | 宿主侧：①agent_tool 增 risk_level 列（V+1 迁移）或复用 PolicyService 高危策略 ②`ToolExecutor` 权限评估结果为"需审批"且解析到的 HitlGateway 为 NoOp 时 → 拒绝执行（配置 `agent.engine.hitl.fail-closed-on-high-risk`，默认 **true**）③危险 SQL 关键字（delete/drop/truncate）强制走该路径 | ①**负向测试**：摘除 DbHitlGatewayAdapter Bean 后，高危工具请求必须被拒（错误码明确）②正向回归：真实网关在位时审批流通过 ③低危工具不受影响 | 配置置 false 回到 fail-open 旧行为 | 2 人日 |
| **AE-P2-7** | 路由回退告警 | 无 | `AiChatController`：`"wenshi".equals(engine) && wenshiReasoningEngine == null` 分支打 WARN + Micrometer 计数器 `engine.routing.fallback.total` | 单测覆盖回退分支日志与计数；Grafana 看板可选加面板 | 纯增量，revert 即可 | 0.5 人日 |

阶段 1 出口准则：全量 `mvn test` 绿 + JaCoCo 棘轮不降 + AE-P0-1/0-2 负向测试在 CI 常驻。**全程无协议变更、无事件语义变更**。

## 4. 阶段 2：数据驱动接线（≈12 人日 + 复评等待期）

```
AE-P1-3 复评自动化(1d) ──► 知识库灌入(平台负责人,非开发) ──► 复评跑批(45号同题集)
                                                              │ Δ质量≥+5%？
                              ┌───────────────────────────────┤
                              否→ 维持 legacy，决策跟踪表记录     是→ AE-P0-3 已就绪
                                                                  ▼
                              AE-P1-1 运行时适配(3d)+接线(1d) ◄── 阶段0 勘定结论
                              AE-P1-8 D-5 同窗(2d)
                              AE-P1-2 模型路由(1d，独立轨：配置三档特征→重跑基准)
```

| 工作项 | 目标 | 前置 | 改动点 | 验收标准 | 回滚 | 工作量 |
|---|---|---|---|---|---|---|
| **AE-P1-3** | 复评触发器自动化 | 无 | 评测跑批器挂定时巡检：`wenshi_semantic_fragment` 行数 ≥ 阈值（建议 500，知识库负责人可调）或 KnowledgeIngestion 完成事件 → 触发 45 号同题集评测 → 结果写决策跟踪表 + 通知 | 巡检任务可见、可手动触发；评测报告落 docs/evaluation/ | 移除定时配置 | 1 人日 |
| **AE-P1-1** | D-6 接线（System2→PLAN_EXECUTE） | 复评达标 + AE-P0-3 + 阶段 0 勘定 | ①**运行时适配**：PlanExecuteRuntime 事件透传补 metadata 字段（ask_user/plan_* 等存活——HITL 回归红线）；PARALLEL/MERGE 伪节点按 NodeType 过滤（或声明 v1 串行语义并文档化）；AgentTask 接口适配 ②**接线**：execute/executeStream 按 `systemChoice.runtimeMode` 分派，配置 `agent.engine.cognition.system2-runtime=PLAN_EXECUTE\|REACT`（默认 REACT=灰度关） | ①适配单测：子步骤事件 metadata 完整性 ②灰度开启后基准题集无质量回退、无 HITL 断链 ③orchestration_node_execution 账本写入放大压测（阶段 0 决定是否节点级共用） | 配置默认 REACT 一键回退 | 适配 3 + 接线 1 = 4 人日 |
| **AE-P1-8** | D-5 真实反思循环 | 与 AE-P1-1 同窗 | ReflexionRuntime 实现 Critic 失败→reflect→注入重跑 ≤3 轮（复用 ReflectionEngine SPI + Wenshi 适配器） | 单测：Critic 通过=单次执行；失败=≤3 轮重试后升级；与 AE-P1-1 灰度同开关族 | 同上 | 2 人日 |
| **AE-P1-2** | 模型路由生效 | `gewu.llm.routing.models` 三档特征配置（轻量/标准/强力） | 配置 + ModelSelectorAdapter 联调（代码已在 execute 路径生效，roadmap V1.2 已完成接入） | 45 号结论 1 同规则重跑基准：Δ质量显著才默认开启，否则保留请求级开关 | 删配置即回退 | 1 人日 |
| **AE-P1-4** | CapabilityCard/GoalPlanner 决策 | 阶段 0 | 决策：Supervisor 接 CapabilityCard 路由（1 人日）或删卡文档化；LlmGoalPlanner 灰度开启评估 | 决策记录 + 对应实施 | 移除接线 | 1 人日 |
| **AE-P1-5** | 12 角色提示词填充 | 内容设计（SDLC 角色 03 参与） | RoleRegistry 内置角色 systemPromptTemplate 内容资产 | 每角色冒烟：角色执行产出符合能力卡声明 | 内容回滚 git revert | 2 人日 |
| **AE-P1-6** | TOOL 参数结构化渲染（L3） | 无 | VariableTemplates：TOOL arguments 改 JSON 感知渲染（占位符替换后走合法化序列化） | 单测：上游含引号/换行的值不碎；Schema 校验通过率不降 | revert | 1 人日 |
| **AE-P1-7** | 回归测试补齐（L4） | AE-P0-1 落地 | F-01/F-02/F-03 路径 Service 级测试（流式 pause/cancel 状态桥接、ScheduleRunner CAS 窗口、SUBGRAPH 引擎层） | 49 号 L4 清单逐项覆盖，CI 常驻 | — | 随 AE-P0-1 + 1 人日 |

## 5. 阶段 3：工程质量（≈8 人日，行为保持重构）

| 工作项 | 内容 | 验收 | 回滚 | 工作量 |
|---|---|---|---|---|
| **AE-P2-1** | 内核拆分：内置五件套外移为引擎内 ToolProvider（保留"不走 ToolExecutor 安全线"语义标注）、事件映射器、预算钩子抽组件——**纯结构重构，行为零变更** | 全量测试绿；JaCoCo core 包覆盖率不降反升；类行数 <800/类 | git revert（单 PR 单职责） | 3 人日 |
| **AE-P2-2** | 状态 enum 化：agent_execution/orchestration_* 状态改 enum（DB 迁移 V+1 同步字典表或保持 varchar+应用层 enum） | 编译期消灭字符串比较；迁移脚本可重入 | 迁移回滚脚本 | 2 人日 |
| **AE-P2-3** | MessageBuilder 收口：技能段注入改为 DefaultMessageBuilder 扩展点，消除双处维护 | 14 篇提示词清单更新；对话回归冒烟 | revert | 1 人日 |
| **AE-P2-4** | 文档收口：docs/agent-engine/03 篇按 2269 行现状重写；29 号标 ⏳ 项转本计划并注 superseded；SPI 口径统一（以 §3.11 表为准） | 文档评审（THEMIS 走查）通过 | — | 1 人日 |
| **AE-P2-5/6** | 直连对话 system 消息兜底（可配默认提示词）；Firecracker 投入/移除决策 | 决策记录 + 对应小改 | 配置回退 | 0.5+0.5 人日 |

## 6. 阶段 4：立项级演进（不排期，条件触发）

| 项 | 触发条件 | 决策内容 |
|---|---|---|
| 单轨统一再决策 | 编排图 HUMAN/TOOL 治理能力（权限矩阵/委托/待办/通知）追平 veloflow | ADR-001 落地 vs 双轨互操作冻结为正式契约（加集成测试）——沿长期-10 准则 |
| **多副本化**（AE-P0-5 + 报告 P2-8 同窗） | 部署形态升级决策 | 分布式锁选型（Redisson vs DB CAS）、`gewu.sse.distributed=true`、veloflow 引擎锁替换、**45 号 interface 挂起/Tomcat 3.3 升级同窗评估**、引擎内存态执行上下文粘性路由方案 |
| 上下文压缩深化 | AE-P1-1 接线后长会话实测不达标 | ContextCompactor 宿主实现分级摘要（会话/任务/工具结果分级保留，压缩即入档 wenshi 记忆库） |
| 评测 CI 门禁 | 阶段 2 复评基建稳定 | 引擎核心包 JaCoCo 棘轮（≥0.70 已在）叠加质量基准棘轮 |

## 7. 里程碑与依赖

```
GATE批准 ─► 阶段0(0.5d) ─► 阶段1(6.5d) ─► 出口准则 ─┬─► 阶段2(12d+复评等待) ─► D-6/D-5灰度决策
                                                     └─► 阶段3(8d，可与阶段2并行·注意AE-P2-1与AE-P1-1避免同文件冲突：拆分先做或分支隔离)
阶段2复评不达标 ─► 维持现状+决策跟踪表 ─► 知识库再灌入后复评（有界循环，沿45号规则）
阶段4：条件触发，不占排期
```

## 8. 风险登记册（Top5）

| 风险 | 影响 | 缓解 |
|---|---|---|
| AE-P0-1 暂停语义变化引 resume 回归 | 编排暂停恢复链路 | 守卫为增量分支 + F-03 专项回归（AE-P1-7）+ 与 OrchestrationService resume 联测 |
| AE-P0-4 fail-closed 默认开启阻断存量高危工具调用 | 生产对话中断 | 上线前跑高危工具清单盘点；配置可置 false 快速回退；灰度=先 observe（仅告警不拒绝）一周再 enforce |
| AE-P1-1 事件 metadata 透传修复不全 | HITL ask_user 断链（评审 F-01 明示风险） | 适配单测断言 metadata 完整性为硬门；灰度默认 REACT |
| 复评长期不达标，阶段 2 悬置 | 接线投入沉没 | 预承诺规则防加码（43 号）；适配成果以运行时可用性独立兑现（不依赖复评结果） |
| AE-P2-1 拆分与 AE-P1-1 改同一文件冲突 | 合并冲突/回归 | 时序隔离：P2-1 先行或分支隔离；P2-1 期间冻结 P1-1 对 ReactAgentExecutor 的改动 |

## 9. 不变量守护（四条红线 → 门禁落点）

1. **零业务依赖**：CI 加 maven-dependency 检查（gewu-agent-engine pom 禁止 com.gewu.*）——阶段 1 随出口准则落地；
2. **AgentEvent 向后兼容**：AE-P1-1 适配的事件透传单测即守护（metadata 字段白名单断言）；
3. **NoOp 兜底 + 安全类 fail-closed**：AE-P0-4 落地后新增 SPI 必须声明语义归类（PR 模板检查项）；
4. **预承诺决策**：AE-P1-1/AE-P1-2 开工前在决策跟踪表登记规则与回滚（沿 43 号格式）。

## 10. 治理与追踪

- **审批**：本计划开工 = R2 代码变更，需 GATE 批准（阶段 1 批准即含阶段 1 全部工作项；阶段 2/3 届时另行确认）；
- **缺陷单**：AE-P0-1/AE-P0-2 走 AEGIS 三单（阶段 0 立单），修复合入回 CR 复验 + UTSS 回归；
- **留痕**：全程 AETC（TASK_OPEN/STEP_DONE/检查点：阶段 1 开工前建 checkpoint `cp-ae-phase1`）；
- **评审闭环**：RPT-002 状态 ruled → 本计划批准时由独立评审代理复审报告修订与计划覆盖度 → closed；
- **正本关系**：本计划为 agent 引擎升级唯一执行正本；roadmap P2-11 行在阶段 0 勘定后更新互链，避免双正本。
