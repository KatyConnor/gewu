# 工作流能力重设计 · 实施方案计划

> 文档编号：EXEPLAN-WF-2026-09
> 版本：V1.0 · 2026-09-25
> 状态：待评审（依据 docs/design/48-workflow-capability-redesign.md）
> ⚠️ 排序调整（2026-09-25）：**编排引擎先行**——本计划 S2/S3（编排生产化、触发协同）由 `orchestration-completion-plan-2026-09.md` 取代并细化；**S1（BPM 审批流）冻结延后**，待编排补全验收后以 48 号 A 域为基线重新分析设计流程引擎
> 代码基线：分支 `fix/sandbox-security-storage`；Flyway 迁移自 **V49** 起编号
> 管控约定：全程 AETC 留痕；R2 操作（改既有接口/表结构迁移）先 GATE_REQUEST + 检查点；代码过 CR-RULES P0/P1；界面过 UI-STD-001 U-CHK 25 项

---

## 一、范围

**做**：48 号文档 A 域（BPM 审批流可用性闭环）、O 域（编排生产化补齐）、C 域（触发与协同）所列 WFA-01~10、WFO-01~08、WFC-01~04。

**不做**（本期明确排除）：BPM 原语级 rollback 节点、BPM subprocess 执行语义、BPM↔编排互调、邮件/企微通知渠道、Quartz 集群调度、BPM 23 种自动化节点（永久废弃）。

## 二、阶段总览

| 阶段 | 内容 | 优先级 | 估算 | 出口标准 |
|---|---|---|---|---|
| S0 | 准备与基线 | — | 0.5 人日 | 检查点建立、分支确认、Flyway 编号确认 |
| S1 | BPM 审批流可用性闭环 | P0 | 14~17 人日 | 端到端冒烟：设计→保存→发布→发起→待办办理→完成，全程 UI 操作无 API 直连 |
| S2 | 编排引擎生产化 | P1 | 10~13 人日 | 版本化/检查点持久化/SUBGRAPH/节点重试超时落地并通过测试 |
| S3 | 触发体系与协同 | P1~P2 | 7~10 人日 | Agent 会话内可触发编排图；定时/Webhook 触发有记录可追溯 |
| S4 | 验收与文档收尾 | — | 1.5 人日 | UAT 冒烟清单通过、两份手册更新、文档索引更新 |

总计约 **33~42 人日**。S1 与 S2 无相互依赖可并行；S3 依赖 S2（版本化后触发才有意义）与 S1 的通知底座。

---

## 三、阶段 S0：准备与基线

| 任务 | 内容 | 级别 |
|---|---|---|
| T0.1 | 从当前分支拉出特性分支 `feat/workflow-redesign`（或按团队惯例拆子分支） | R1 |
| T0.2 | `bash audit/tools/checkpoint.sh create cp-workflow-redesign-start`；确认 V26~V48 迁移编号占用、测试 H2 schema 位置 | R1 |
| T0.3 | 冒烟基线：手工记录当前工作流页/编排页行为（截图 + 请求录），作为回归对照 | R0 |

## 四、阶段 S1：BPM 审批流可用性闭环（P0）

### 任务分解

| 任务 | 内容 | 涉及文件 | 依赖 | 估算 | 级别 |
|---|---|---|---|---|---|
| T1.1 | 后端 BPM 图校验器 `WorkflowGraphValidator`（WV-01~08），保存（`saveWorkflowGraph`）与发布（`publishWorkflow`）双闸接入，返回结构化问题清单；顺带修复 transition from/to 为空"臆造连线"行为（改为校验错误） | `gewu-application/.../workflow/WorkflowService.java`、新增 validator | — | 2 人日 | **R2**（改既有接口行为） |
| T1.2 | 前端节点模型收敛：重写 `workflowTypes.ts` 为 6 节点目录（start/end/task/approval 双出口/condition/subprocess 禁拖），ConfigField 声明式表单沿用 | `workflowTypes.ts` | — | 1 人日 | R1 |
| T1.3 | 审批流设计器重做：`WorkflowDesigner.tsx`（React Flow）+ 转换层 `workflowTransformer.ts`（画布结构 ↔ `SaveWorkflowGraphCommand` DTO）+ 节点卡片/属性面板/校验面板；接 `PUT/GET /{id}/graph`；替换 `WorkflowCanvas.tsx` | `gewu-web/src/components/pages/workflow-designer/`（新组件族）、`WorkflowPage.tsx`、`lib/workflow.ts`（补 graph API 封装） | T1.1, T1.2 | 4~5 人日 | R1（新增）/ R2（替换页面） |
| T1.4 | 状态字面量统一：后端收敛 `WorkflowStatus` 枚举 code；前端状态映射函数一处定义 | `WorkflowInstanceService.java`、`WorkflowPage.tsx`、`lib/workflow.ts` | — | 0.5 人日 | R2 |
| T1.5 | 发起实例变量表单：动态 KV 编辑 + title；提交 `startInstance` | `WorkflowPage.tsx` | T1.4 | 1 人日 | R1 |
| T1.6 | 我的待办 + 办理抽屉：`listMyInstances` 聚合、task 提交（completeNode + output）、approval 通过/驳回（approved + comment）；越权 403 后端校验（assignee 本人/角色成员，config.assigneeRole 解析） | `WorkflowPage.tsx`（新增 Tab）、`WorkflowInstanceService.java`、`WorkflowInstanceController.java` | T1.4 | 2.5~3 人日 | **R2**（completeNode 权限语义变更） |
| T1.7 | 审批驳回条件边路由：completeNode(approved=false) 写 `variables.approved=false`+意见后按条件边求值推进（既有 evaluateCondition 增强：支持布尔与 true/false 字面比较）；提供画布"审批模板"快捷创建（通过/驳回双出边带默认表达式） | `WorkflowInstanceService.java`、设计器模板 | T1.6 | 1 人日 | R2 |
| T1.8 | 通知接线：TASK_CREATED/APPROVAL_REQUIRED/COMPLETED/TERMINATED 写通知 + SSE 站内推送；前端通知列表与未读数 | `WorkflowInstanceService.java`、SSE 通道、新通知组件 | T1.6 | 1.5 人日 | R1 |
| T1.9 | S1 集成测试：后端单测（validator/completeNode 路由/权限）+ H2 schema 更新 + 前端冒烟脚本清单 | 测试目录 | T1.1~T1.8 | 1.5 人日 | R1 |

### S1 验收（用户旅程冒烟）

1. 创建"需求-审批-发布"工作流 → 画布拖 start→task→approval(通过→end；驳回→task) → 保存 → 刷新还原；
2. 缺 end 时发布被阻，校验面板点击定位；
3. 发起实例填变量 → 实例 running；发起人收到通知；
4. 办理人"我的待办"看到任务 → task 提交 output → approval 驳回 → 流程走驳回分支回到 task → 再次提交 → approval 通过 → end，实例 completed；
5. 无权限用户办理返回 403；删除工作流有二次确认。

## 五、阶段 S2：编排引擎生产化（P1）

| 任务 | 内容 | 涉及文件 | 依赖 | 估算 | 级别 |
|---|---|---|---|---|---|
| T2.1 | 版本化：V49 迁移建 `orchestration_graph_version`；激活时写版本快照；执行加载定义优先取版本快照（失败回退 graph_definition）；execution 表加 version_id/trigger_type/retry_count（V50） | Flyway V49/V50、`OrchestrationService.java`、实体/Mapper | S0 | 2 人日 | **R2**（DB 迁移 + 既有执行链路径） |
| T2.2 | 下架/回滚：`deactivate`（active→draft）、版本列表、`rollback` API；设计器版本历史面板（列表/JSON diff/回滚/下架入口，危险操作二次确认） | `OrchestrationController`、`OrchestrationService`、designer 组件 | T2.1 | 1.5 人日 | R1（新端点）|
| T2.3 | 检查点持久化：V51 建 `orchestration_checkpoint`；暂停落库/恢复读取/恢复成功删除；恢复接口逻辑改造（内存命中→DB 回退） | Flyway V51、`ExecutionControl`、`OrchestrationService` | — | 1.5 人日 | **R2** |
| T2.4 | SUBGRAPH：引擎实现（加载子图、上下文继承、输出回写、深度≤2）；VL-13 校验（active 图引用、禁自引用/环）；前端解禁 + refId 下拉 + 单测（嵌套/环/失败传播） | `PipelineModeHandler`、`GraphDefinitionValidator`、designer | — | 2.5 人日 | R2（核心调度器改动） |
| T2.5 | 节点级重试/超时：AGENT/TOOL config `retryCount/retryBackoffMs/timeoutSeconds`；执行器包装重试循环（受 continueOnFailure 约束）；节点记录补 retry_count；设计器属性面板加字段 | `PipelineModeHandler`、`GraphNodeExecutor`、designer | — | 1.5 人日 | R2 |
| T2.6 | executionMode 从属性面板移除（D4）；变量语法对齐（WFO-06：VL-09 改无前缀、渲染/校验同源、`${var.x}` 兼容降级） | `DesignerPropertyPanel.tsx`、`orchestrationDesigner.ts`、`VariableTemplates.java`、validator | — | 1.5 人日 | R2 |
| T2.7 | S2 测试：版本化迁移幂等测试、检查点重启恢复测试（模拟重启）、SUBGRAPH/重试单测；更新 9 个既有编排测试 | 测试目录 | T2.1~T2.6 | 1 人日 | R1 |

## 六、阶段 S3：触发体系与协同（P1~P2）

| 任务 | 内容 | 涉及文件 | 依赖 | 估算 | 级别 |
|---|---|---|---|---|---|
| T3.1 | Agent 工具化 `run_orchestration_graph`：工具定义（graphId/input 参数 schema）+ active 校验 + 同步执行（预算上限：复用引擎 Token 预算 + 工具级时长帽）+ 执行记录 session_id 贯通；会话侧无 UI 改动（ReAct 自主调用） | agent-engine 工具注册处、`OrchestrationService` | T2.1 | 2 人日 | R1（新增工具）/ R2（注册表改动） |
| T3.2 | 定时触发：V52 建 `orchestration_schedule`；cron 解析（Spring CronExpression）+ 每分钟扫描（@Scheduled + DragonflyDB SETNX 防重）+ 以系统身份触发 + design 器"触发配置"入口（cron 校验、下次触发预览、启停） | Flyway V52、新调度组件、designer | T2.1 | 2 人日 | R1 |
| T3.3 | Webhook 触发：V53 建 `orchestration_webhook`（token SM3 哈希）；生成/重置/停用 API；匿名端点（SecurityConfig 白名单 + 404 语义）；body→input 触发 | Flyway V53、`OrchestrationController`、`SecurityConfig` | T2.1 | 2 人日 | **R2**（安全配置白名单变更，需安全评审） |
| T3.4 | 触发类型可观测：执行列表/详情展示 triggerType；统计卡片分列 | `OrchestrationPage.tsx` | T3.1~T3.3 | 0.5 人日 | R1 |
| T3.5 | S3 测试：定时触发时序、Webhook 安全用例（错误 token/伪造头）、Agent 工具预算熔断 | 测试目录 | T3.1~T3.4 | 1 人日 | R1 |

## 七、阶段 S4：验收与文档收尾

| 任务 | 内容 | 估算 |
|---|---|---|
| T4.1 | UAT 冒烟：按 48 号 §5 各需求验收标准逐条执行并记录 | 0.5 人日 |
| T4.2 | 手册更新：重写 `docs/workflow-canvas-manual.md`（按 6 节点现实能力，删除愿景内容）；`docs/orchestration-engine-manual.md` 增补版本化/SUBGRAPH/重试/触发章节 | 0.5 人日 |
| T4.3 | `docs/README.md` 文档索引更新（48 号 + 本计划）；28 号文档头部加"V1.3 修订指引"链接 48 号；admin-web 死常量清理 | 0.5 人日 |

---

## 八、测试与质量门

| 关卡 | 要求 |
|---|---|
| 单元测试 | 新增校验器/路由/权限/版本化/检查点/SUBGRAPH/重试逻辑均有单测；编排既有 9+4 个测试类保持全绿 |
| 迁移测试 | V49~V53 在 H2（测试）与 MySQL/OceanBase（本地 compose）各跑一遍，幂等可重放 |
| 前端 | UI-STD-001 U-CHK 25 项自检；四主题切换无硬编码色 |
| CR 门 | CR-RULES P0/P1（L3-SEC-*）全过：重点——表达式求值注入、Webhook 匿名端点、办理越权、`${}` 模板渲染 |
| 回归 | S0 冒烟基线对照：编排设计器既有功能（46 号 FR-01~14）无回归 |

## 九、风险与回滚

| 风险 | 缓解/回滚 |
|---|---|
| T1.1/T1.7 改变既有保存/办理行为 | R2 门：GATE_REQUEST 附行为差异说明；检查点 `cp-before-s1-backend`；失败 restore |
| V49~V53 迁移失败 | 每个迁移独立文件、可独立回退（drop table）；上线前本地全量演练 |
| T2.4 触碰调度核心 | SUBGRAPH 独立方法 + 特性开关（配置项默认关，验证后开） |
| T3.3 匿名端点风险 | 安全评审 + token 强随机（SM3 存储）+ 限流 + 404 语义；可配置总开关 |
| 前端设计器替换回归 | WorkflowCanvas 保留一个迭代（路由开关可切回），下迭代删除 |

## 十、AETC 留痕约定

- 每阶段开始：TASK_OPEN/CORRECTION 续接记录 + 检查点（R2/R3 前置）；
- 每个 R2 任务：RISK_PRE（影响文件清单 + 回滚方式）→ GATE_REQUEST → RISK_POST；
- 阶段出口：STEP_DONE 汇总 + 验收证据（测试输出/截图路径）；
- 收尾：TASK_CLOSE 附自检清单与复核建议（48 号设计文档、迁移脚本、安全相关改动建议人工复核）。
