# 编排引擎能力补全 · 实施方案计划

> 文档编号：EXEPLAN-ORCH-2026-09
> 版本：V1.0 · 2026-09-25
> 状态：待评审
> 需求基线：docs/design/48-workflow-capability-redesign.md O 域（WFO）+ C 域（WFC），并新增 WFO-09
> 代码基线：分支 `fix/sandbox-security-storage`；Flyway 迁移自 **V49** 起编号（V26~V48 已占用）
> 排序决策（2026-09-25，用户拍板）：**编排引擎先行补全；BPM 流程引擎（48 号 A 域）本期冻结**，待编排补全完善后重新分析设计。本计划取代总计划 `workflow-redesign-implementation-plan-2026-09.md` 中的编排相关阶段（S2/S3），总计划 S1（BPM）无限期延后。
> 管控约定：全程 AETC 留痕；R2 操作先 GATE_REQUEST + 检查点；代码过 CR-RULES P0/P1；界面过 UI-STD-001 U-CHK 25 项；编码叠加 CODING-STD-001。

---

## 目录

1. [背景与范围](#一背景与范围)
2. [需求基线汇总](#二需求基线汇总)
3. [阶段总览](#三阶段总览)
4. [阶段 O1：可靠性与版本化基座](#四阶段-o1可靠性与版本化基座)
5. [阶段 O2：结构与人工介入能力](#五阶段-o2结构与人工介入能力)
6. [阶段 O3：触发体系与集成](#六阶段-o3触发体系与集成)
7. [阶段 O4（可选）：executionMode 接线](#七阶段-o4可选executionmode-接线)
8. [阶段 O5：验收与文档收尾](#八阶段-o5验收与文档收尾)
9. [测试与质量门](#九测试与质量门)
10. [风险与回滚](#十风险与回滚)
11. [AETC 留痕约定](#十一aetc-留痕约定)

---

## 一、背景与范围

### 1.1 背景

编排引擎（orchestration，`gewu-agent-engine` + `gewu-application/orchestration` + `OrchestrationController` + 前端 `OrchestrationPage`/`OrchestrationDesigner` 组件族）经 46 号（设计器）与 47 号（可靠性四修复）两轮迭代后，已闭环：可视化设计、VL-01~12 结构校验双闸、四编排模式、8 类节点（7 类可用）、同步/流式执行、暂停/恢复/取消、节点级执行记录与回放（FR-14）、HITL 审批中心。

剩余能力缺口（48 号 §1.3）：触发方式单一、版本管理薄弱、检查点仅内存、SUBGRAPH 未实现、executionMode 声明不生效、节点级重试/超时缺失、HUMAN 审批配置占位、变量语法不一致。另经本轮核实补充一项：**断点续跑事件流前端零消费**（`grep resume/stream gewu-web/src` 零命中，手册 FAQ Q6 确认"续跑流可通过 API 消费"——UI 只做了恢复第一步）。

### 1.2 范围

**做**：48 号 O 域 WFO-01~08 + C 域 WFC-01~04 + 新增 WFO-09，共 13 项需求。

**不做**（本期冻结，随 BPM 重设计另行立项）：BPM 审批流全部需求（48 号 A 域 WFA-01~10）、BPM↔编排互调、BPM 手册改写；admin-web 死常量清理随 BPM 阶段一并处理。

**顺手项（编排范围内低成本清理）**：`GraphNodeExecutor.java:55` "TOOL 节点暂不绑定 Agent 级 ToolConfig" 注释债——随 WFO-05 一并评估接线或移除声明。

## 二、需求基线汇总

| 编号 | 需求 | 优先级 | 摘要（详见 48 号 §5.2/§5.3） | 所属阶段 |
|---|---|---|---|---|
| WFO-01 | 图版本化 | P1 | `orchestration_graph_version` 版本快照表；激活即发布新版本；执行绑定 versionId；草稿始终可编辑 | O1 |
| WFO-02 | 下架/版本回滚 | P1 | active→draft 下架；历史版本快照写回草稿；版本列表 + JSON diff | O1 |
| WFO-03 | 检查点持久化 | P1 | `orchestration_checkpoint` 表；暂停落库/恢复读取/成功即删；重启后可续跑 | O1 |
| WFO-04 | SUBGRAPH 嵌套子图 | P1 | 引擎实现（refId 指向 active 图、上下文继承、输出回写、深度≤2）；VL-13 校验；前端解禁 | O2 |
| WFO-05 | 节点级重试与超时 | P1 | AGENT/TOOL 节点 `retryCount/retryBackoffMs/timeoutSeconds`；受 continueOnFailure 约束；记录 retry_count | O1 |
| WFO-06 | 变量语法对齐 | P2 | 统一 `${name}`；VL-09 与渲染同源；`${var.x}` 兼容降级 | O1（小） |
| WFO-07 | HUMAN 节点审批配置 | P2 | config `assigneeId/assigneeRole` 审批人圈定；审批中心按人过滤 | O2 |
| WFO-08 | executionMode 收敛 | P2 | 本期 UI 移除（O1）；PLAN_EXECUTE 接线列 O4 可选 | O1 + O4 |
| WFO-09 | 断点续跑前端闭环（新增） | P1 | 前端封装 resume/stream SSE；恢复成功自动续订事件流，执行历史/设计器画布同步着色直至终态 | O2 |
| WFC-01 | Agent 工具化调用编排图 | P1 | `run_orchestration_graph` 工具（Tool 接口 + @ToolProvider 自动注册）；active 校验 + 预算上限；session_id 贯通 | O3 |
| WFC-02 | 定时触发 | P2 | `orchestration_schedule` 表 + @Scheduled 扫描 + SETNX 防重；设计器触发配置入口 | O3 |
| WFC-03 | Webhook 触发 | P2 | `orchestration_webhook` 表（token SM3）；匿名端点 + 安全防护；body→input | O3 |
| WFC-04 | 触发类型可观测 | P2 | 执行记录/列表/统计卡展示 triggerType（MANUAL/API/AGENT_TOOL/SCHEDULE/WEBHOOK） | O3 |

## 三、阶段总览

| 阶段 | 主题 | 需求 | 估算 | 出口标准 |
|---|---|---|---|---|
| O0 | 准备与基线 | — | 0.5 人日 | 检查点建立、迁移编号确认、冒烟基线记录 |
| O1 | 可靠性与版本化基座 | WFO-01/02/03/05/06 + WFO-08(UI) | 8.5~9.5 人日 | 版本化生效、重启可续跑、节点重试/超时可用，既有测试全绿 |
| O2 | 结构与人工介入能力 | WFO-04/09/07 | 5~5.5 人日 | 两级嵌套子图跑通；UI 暂停→恢复全程可视化到终态 |
| O3 | 触发体系与集成 | WFC-01/02/03/04 | 7~8 人日 | 会话内 Agent 可触发图执行；定时/Webhook 有记录可追溯 |
| O4（可选） | executionMode 接线 | WFO-08(接线) | 2 人日 | PLAN_EXECUTE 行为可区分并有测试 |
| O5 | 验收与文档收尾 | — | 1.5 人日 | UAT 冒烟通过、手册更新、索引更新 |

**合计约 22.5~25 人日（含 O4 可选 24.5~27）。** O1→O2→O3 串行推进（O2 的 SUBGRAPH 与 O1 无代码冲突，可与 O1 后半并行）；O3 依赖 O1（执行记录 versionId/triggerType 列在 V50）。

## 四、阶段 O1：可靠性与版本化基座

### 任务分解

| 任务 | 内容 | 涉及文件 | 依赖 | 估算 | 级别 |
|---|---|---|---|---|---|
| T-O1.1 | **版本化（WFO-01）**：V49 建 `orchestration_graph_version`；激活（含再激活）时将当前定义快照为不可变版本（version 自增）；存量 active 图一次性迁移写入 version 1；V50 给 `orchestration_execution` 加 `version_id`/`trigger_type`/`retry_count` 列；执行加载定义改为优先按 versionId 取快照，未命中回退 `graph_definition`（兜底 + 告警日志） | Flyway V49/V50、`OrchestrationGraphVersionEntity/Mapper`（新）、`OrchestrationService.java`（激活/执行加载路径）、`OrchestrationExecutionEntity` | O0 | 2 人日 | **R2**（DB 迁移 + 既有执行链路径变更） |
| T-O1.2 | **下架/回滚（WFO-02）**：新增 `PUT /graphs/{id}/deactivate`（active→draft）、`GET /graphs/{id}/versions`、`POST /graphs/{id}/versions/{versionId}/rollback`（快照写回草稿）；设计器/列表页接入版本历史面板（列表、JSON diff 展示、下架/回滚入口，二次确认明示连带影响） | `OrchestrationController.java`、`OrchestrationService.java`、`lib/orchestration.ts`、`OrchestrationPage.tsx`/designer | T-O1.1 | 1.5 人日 | R1（新端点） |
| T-O1.3 | **检查点持久化（WFO-03）**：V51 建 `orchestration_checkpoint`（uk: execution_id）；暂停时**双写**（内存注册表 + DB）；恢复接口读取顺序：内存 → DB（命中后删除）；进程重启后恢复返回 `resumable=true` | Flyway V51、`OrchestrationCheckpointEntity/Mapper`（新）、`ExecutionControl`、`OrchestrationEngine.java:74-79`（resume 路径）、`OrchestrationService.java` | — | 1.5 人日 | **R2**（恢复逻辑变更） |
| T-O1.4 | **节点级重试/超时（WFO-05）**：AGENT/TOOL 节点 config 支持 `retryCount`（默认 0 上限 3）/`retryBackoffMs`（默认 1000）/`timeoutSeconds`（缺省继承引擎级）；执行器包装重试循环与超时中断，超时按失败处理并受 continueOnFailure 语义约束；节点执行记录写 retry_count；设计器属性面板补三字段（含上限提示）；顺带评估 TOOL 节点绑定 ToolConfig 的接线或移除声明债 | `PipelineModeHandler.java`（executeAgentNode/executeToolNode）、`GraphNodeExecutor.java`、designer 属性面板 | — | 1.5 人日 | R2 |
| T-O1.5 | **变量语法对齐（WFO-06）**：VL-09 校验对象改为 `${name}` 无前缀写法（可解析集合 = 图变量 + input + 前驱节点产出）；渲染与校验同源（同一变量集合判定）；`${var.x}` 兼容期自动降级为 `${x}` 并记告警；设计器提示文案同步 | `GraphDefinitionValidator.java`、`VariableTemplates.java`、`orchestrationDesigner.ts`、设计器面板提示 | — | 1 人日 | R2 |
| T-O1.6 | **executionMode UI 移除（WFO-08 前半）**：属性面板删除 executionMode 下拉（枚举与后端字段保留，O4 再接线）；手册同步 | `DesignerPropertyPanel.tsx`、手册 | — | 0.25 人日 | R1 |
| T-O1.7 | **O1 测试**：版本化（快照生成/执行绑定/回退兜底）、下架回滚、检查点（模拟进程重启后 DB 恢复）、重试退避与超时、VL-09 新语义；更新 H2 schema；既有 9+4 个编排测试类全绿 | gewu-agent-engine / gewu-application 测试目录 | T-O1.1~T-O1.6 | 1.25 人日 | R1 |

### O1 验收

1. 激活图产生 version 1 → 下架改图 → 再激活产生 version 2；v1 期间的执行记录回放仍按 v1 快照着色；
2. 执行中暂停 → **重启后端服务进程** → 恢复接口返回 `resumable=true`，断点续跑成功且已完成节点跳过；
3. 配置 `retryCount=2` 的 AGENT 节点遇 LLM 瞬时 5xx 自动重试成功，节点记录显示 retry_count=1；
4. `${var.input}` 存量图执行不再渲染空串（降级为 `${input}` 并有告警日志）。

## 五、阶段 O2：结构与人工介入能力

| 任务 | 内容 | 涉及文件 | 依赖 | 估算 | 级别 |
|---|---|---|---|---|---|
| T-O2.1 | **SUBGRAPH（WFO-04）**：引擎实现——`PipelineModeHandler` 新增 SUBGRAPH 分支（替换 default 按 AGENT 的落空行为）：refId 加载 active 子图定义（优先版本快照）→ 以父上下文变量为初始变量独立执行 → 子图最终输出写入父上下文（变量名=节点 ID）→ 深度≤2；VL-13 校验（refId 存在且 active、禁自引用、跨图引用环检测）；配置开关 `agent.engine.orchestration.subgraph.enabled`（默认 false，验证后开）；前端解除置灰、refId 下拉（数据源：active 图列表，新增目录端点或复用 `GET /graphs?status=active`） | `PipelineModeHandler.java:198-206`、`GraphDefinitionValidator.java`、`OrchestrationCatalogService.java`、`DesignerPalette.tsx`、属性面板 | O1 | 2.5 人日 | **R2**（核心调度器改动 + 特性开关） |
| T-O2.2 | **断点续跑前端闭环（WFO-09）**：`lib/orchestration.ts` 补 `resumeGraphStream`（SSE 封装，复用 executeGraphStream 的解析与 AbortController 模式）；执行历史"恢复"按钮两步化：调 resume 接口 → `resumable=true` 时自动续订续跑流，事件面板滚动展示并按 nodeId 归因着色直至 `graph_complete` 终态；设计器运行控制台复用同一通道 | `lib/orchestration.ts`、`OrchestrationPage.tsx`（执行历史区）、`OrchestrationDesigner.tsx`/`DesignerRunConsole.tsx` | O1（检查点） | 1 人日 | R1 |
| T-O2.3 | **HUMAN 审批配置（WFO-07）**：HUMAN 节点 config 支持 `assigneeId`/`assigneeRole`；`approval_required` 事件与 `approval_request` payload 携带审批人信息（经 `DbHitlGatewayAdapter` 落库）；审批中心页编排 Tab 按当前用户过滤（assignee=本人 或 角色成员；未配置维持全员可见） | designer 属性面板、`DbHitlGatewayAdapter.java`、审批中心前端页 | — | 1.5 人日 | R2（审批数据语义扩展） |
| T-O2.4 | **O2 测试**：SUBGRAPH 单测（两级嵌套成功/环拒绝/失败传播/开关关闭回落 AGENT 行为）、续跑流前端联调脚本、审批人过滤用例 | 测试目录 | T-O2.1~T-O2.3 | 0.5 人日 | R1 |

### O2 验收

1. 父图含 SUBGRAPH 节点引用子图，执行后父图 MERGE 汇聚子图产出；将子图引用自身/互相引用时保存被 VL-13 阻断；
2. UI 全程操作：执行 → 暂停 → 执行历史点"恢复" → 无需任何 API 工具，画布续跑着色直至终态；
3. 配置 `assigneeId` 的审批请求仅对指定人可见，他人不可操作。

## 六、阶段 O3：触发体系与集成

| 任务 | 内容 | 涉及文件 | 依赖 | 估算 | 级别 |
|---|---|---|---|---|---|
| T-O3.1 | **Agent 工具化（WFC-01）**：新建 `RunOrchestrationGraphTool implements Tool` + `@ToolProvider(category="ORCHESTRATION")`（自动注册 `ToolRegistry`，经 `ToolExecutor` 安全管线）；参数 schema：`graphId`（必填）/`input`（可选）；执行前校验图存在且 active（优先版本快照）；同步执行 + 单次预算上限（复用引擎 Token 预算体系 + 工具级时长帽，默认 300s 可配 `agent.engine.tool.orchestration.timeout`）；执行记录写 `session_id`（取 ToolContext 会话）与 `trigger_type=AGENT_TOOL` | `gewu-agent-engine/.../tool/`（新工具类）、`OrchestrationService.java`（暴露内部执行入口）、`AgentEngineAutoConfiguration` 装配 | O1 | 2 人日 | R1（新增工具）/ **R2**（工具注册表接线） |
| T-O3.2 | **定时触发（WFC-02）**：V52 建 `orchestration_schedule`（graph_id/cron_expr/timezone/input_template/enabled/last_fire_at/next_fire_at，uk: graph_id 一图一配置）；调度组件 `@Scheduled(cron="0 * * * * *")` 每分钟扫描 `next_fire_at <= now` 到期行，DragonflyDB `SETNX` 锁防重，命中则以系统身份发起执行（`trigger_type=SCHEDULE`）并回写 fire 时间；设计器"触发配置"面板：cron 编辑（Spring `CronExpression` 校验 + 下次 5 次触发时间预览）+ 启停开关 | Flyway V52、`OrchestrationScheduleEntity/Mapper`（新）、`OrchestrationScheduleRunner`（新）、`OrchestrationController`（schedule 端点）、designer 触发配置面板 | O1 | 2.5 人日 | R1（新增组件） |
| T-O3.3 | **Webhook 触发（WFC-03）**：V53 建 `orchestration_webhook`（graph_id/token_hash SM3/header_check/enabled，uk: token_hash）；API：`PUT /graphs/{id}/webhook` 生成（token 明文仅创建响应返回一次）/重置/停用；触发端点 `POST /api/v1/orchestration/webhooks/{token}` 匿名开放——`SecurityConfig` 白名单该路径 + 常量时间比较 SM3 校验 + 失败统一 404（不暴露存在性）+ 简单限流（DragonflyDB 计数）；命中以 body 整体为 input 异步执行（`trigger_type=WEBHOOK`）；总开关 `agent.engine.webhook.enabled` | Flyway V53、`OrchestrationWebhookEntity/Mapper`（新）、`OrchestrationController`、`SecurityConfig`、designer 触发配置面板（与 T-O3.2 同面板） | O1 | 2 人日 | **R2**（安全配置白名单变更，须安全评审） |
| T-O3.4 | **触发可观测（WFC-04）**：执行列表/详情展示 triggerType 与来源（调度行/webhook token 前缀/会话 ID）；统计卡片按触发类型分列 | `OrchestrationPage.tsx`、`lib/orchestration.ts` | T-O3.1~T-O3.3 | 0.5 人日 | R1 |
| T-O3.5 | **O3 测试**：定时触发时序与 SETNX 防重、Webhook 安全用例（错误 token 404/伪造头/限流）、Agent 工具（参数校验/非 active 拒绝/预算熔断/session_id 贯通） | 测试目录 | T-O3.1~T-O3.4 | 1 人日 | R1 |

### O3 验收

1. 会话中对 Agent 说"运行开发-审查流水线，输入是 xxx"→ Agent 自主调用 `run_orchestration_graph` → 会话内汇报执行结果；编排执行历史出现该条记录（triggerType=AGENT_TOOL，session_id 可追溯）；
2. 配置每小时触发的图按期产生执行记录；停用开关后不再触发；重启进程后调度恢复；
3. 外部 `curl -X POST .../webhooks/{token}` 触发一次执行；错误 token 返回 404；停用后同样 404。

## 七、阶段 O4（可选）：executionMode 接线

| 任务 | 内容 | 估算 | 级别 |
|---|---|---|---|
| T-O4.1 | PLAN_EXECUTE 接线：`PipelineModeHandler.executeAgentNode` 按 `node.executionMode` 分派到 `runtime/PlanExecuteRuntime`（REACT 缺省路径不变）；PLAN_EXECUTE 行为验证与单测；属性面板恢复下拉（标注"生效"） | 1.5 人日 | **R2** |
| T-O4.2 | REFLEXION/TOOL_PARALLEL 评估：可接线则接线，否则从枚举与文档中明确标注"规划中" | 0.5 人日 | R1 |

> 决策依据 48 号 D4：UI 先移除防误导（O1），接线验证成熟后再暴露（O4）。O4 可与 O5 并行或延后至下一迭代。

## 八、阶段 O5：验收与文档收尾

| 任务 | 内容 | 估算 |
|---|---|---|
| T-O5.1 | UAT 冒烟：按 O1~O3 验收标准逐条执行记录证据（截图/请求录） | 0.5 人日 |
| T-O5.2 | 手册更新：`docs/orchestration-engine-manual.md` 增补——版本化与下架/回滚（§5 重写）、SUBGRAPH 节点（§6.3.8）、节点重试/超时（§13 扩展）、变量语法统一（§8.3 修订）、断点续跑 UI 全程（§10.4/Q6 修订）、触发配置（新增 §10.7：定时/Webhook/Agent 工具）、API 参考（§14 增端点）、配置项（附录 D 增开关） | 0.5 人日 |
| T-O5.3 | `docs/README.md` 索引更新（本计划 + 后续 BPM 重设计立项占位）；48 号文档头补排序决策注记；TASK_CLOSE 归档 | 0.5 人日 |

## 九、测试与质量门

| 关卡 | 要求 |
|---|---|
| 单元测试 | 版本快照/回退兜底、检查点重启恢复、SUBGRAPH（嵌套/环/开关回落）、重试退避、VL-09/VL-13、工具参数与预算熔断、定时防重、Webhook 校验——均有单测；既有 9+4 个编排测试类保持全绿 |
| 迁移测试 | V49~V53 在 H2（测试）与 MySQL/OceanBase（本地 compose）各跑一遍；存量 active 图 version 1 迁移幂等可重放 |
| 回归 | 46 号 FR-01~14 设计器既有功能零回归（对照 O0 冒烟基线）；PIPELINE 四模式既有行为不变（重试/超时默认值=现行为） |
| 安全（CR P0/P1） | 重点：Webhook 匿名端点（SM3 常量时间比较、404 语义、限流）、`${}` 模板渲染注入面、SUBGRAPH 子图加载权限（仅 active 且当前用户可见）、Agent 工具预算熔断 |
| 前端 | UI-STD-001 U-CHK 25 项自检；四主题切换无硬编码色；内网零 CDN |

## 十、风险与回滚

| # | 风险 | 等级 | 缓解/回滚 |
|---|---|---|---|
| R1 | T-O1.1 版本化改执行取定义路径，存量执行/图行为漂移 | 中 | 迁移幂等；快照未命中回退 `graph_definition` 兜底；检查点 `cp-before-o1`；上线后抽查历史执行回放一致性 |
| R2 | T-O1.3 检查点双写/读取顺序引入恢复状态不一致 | 中 | 内存命中优先（兼容现状），DB 仅兜底；恢复成功即删检查点；失败路径回归测试覆盖 |
| R3 | T-O2.1 SUBGRAPH 触碰 `PipelineModeHandler` 核心调度 | 中 | 独立方法 + 配置开关默认关 + 深度/环双重护栏 + 单测；开关关闭行为与现状逐字节一致 |
| R4 | T-O1.4 重试/超时改变失败语义（意外重试放大副作用） | 中 | 默认 `retryCount=0`（现行为）；仅 TOOL/AGENT 无副作用类操作建议配置；文档明示重试幂等性要求 |
| R5 | T-O3.3 匿名端点被扫描滥用 | 中 | 安全评审前置（GATE）、token 强随机 SM3、限流、总开关默认关、404 语义 |
| R6 | T-O3.1 Agent 工具放大执行量（Token 成本/并发） | 中 | 单次执行预算上限 + 时长帽 + 会话级频控；工具描述引导 Agent 仅按用户指令调用 |
| R7 | T-O3.2 多实例部署时定时重复执行 | 低 | 单体现状 + SETNX 防重；K8s 多副本前排期升级（48 号开放问题 4 持续跟踪） |

## 十一、AETC 留痕约定

- **O0**：TASK_OPEN（新会话续接本计划，先读 `audit/traces/` 最近 TASK_CLOSE）+ `checkpoint.sh create cp-orch-o0` + 冒烟基线记录（R0）。
- **每个 R2 任务**（T-O1.1/1.3/1.4/1.5、T-O2.1/2.3、T-O3.1 注册表接线、T-O3.3）：RISK_PRE（影响文件 + 回滚方式）→ GATE_REQUEST → 获批执行 → RISK_POST；T-O1.1/T-O2.1/T-O3.3 前另建专项检查点。
- **每阶段出口**：STEP_DONE 汇总（含测试输出路径）+ 阶段检查点（`cp-orch-oN`）。
- **收尾**：TASK_CLOSE 附自检清单；T-O3.3 安全改动与 V49~V53 迁移脚本列入人工复核清单。

---

*本计划为 48 号设计文档的编排轨实施载体；BPM 轨（A 域）冻结，待编排补全验收后启动"流程引擎重新分析设计"专项（届时以 48 号 A 域为输入基线）。*
