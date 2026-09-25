# 工作流能力边界分析与功能架构重设计

> 编号：48-workflow-capability-redesign
> 版本：V1.0 · 2026-09-25
> 性质：功能需求设计（现状分析 + 目标功能架构 + 功能需求清单，供实施立项评审）
> 实施排序（2026-09-25 决策）：**编排引擎（O/C 域）先行**，实施载体 `docs/plan/exe_plan/orchestration-completion-plan-2026-09.md`（并新增 WFO-09 断点续跑前端闭环）；**A 域 BPM 流程引擎冻结**，待编排补全完善后以本文件 A 域为输入基线重新分析设计
> 代码基线：分支 `fix/sandbox-security-storage`（2026-09-25），行号证据以该时点为准
> 关联文档：28（工作流引擎设计 V1.2 边界澄清）、29（编排引擎架构 + ADR-001~004）、30（工作流 API 设计）、31（工作流前端设计）、46（编排设计器调研 FR-01~15）、47（编排引擎可靠性修复）、`docs/requirements/PRD-SRS-GEWU-PLATFORM.md`（F-026/F-030~036、BR-018/022/023/024）

---

## 目录

1. [背景与问题定义](#一背景与问题定义)
2. [现状盘点：两轨能力与边界](#二现状盘点两轨能力与边界)
3. [目标功能架构](#三目标功能架构)
4. [关键设计决策](#四关键设计决策)
5. [功能需求设计](#五功能需求设计)
6. [数据模型设计](#六数据模型设计)
7. [API 设计](#七api-设计)
8. [前端设计](#八前端设计)
9. [兼容性与迁移](#九兼容性与迁移)
10. [风险与开放问题](#十风险与开放问题)

---

## 一、背景与问题定义

用户反馈："当前项目中的工作流还不能使用。" 经全量代码勘察确认，该反馈成立，且需要拆分为两个层面理解——平台存在**两套"工作流"体系**，其可用性状态完全不同：

| 体系 | 前端入口 | 定位（28 号 V1.2 决议） | 当前状态 |
|---|---|---|---|
| **A. BPM 工作流（workflow）** | 左侧导航「工作流」 | 人工审批流：人驱动的流程自动化 | **演示态，硬断点多处，不可用** |
| **B. 编排引擎（orchestration）** | 左侧导航「编排引擎」 | 智能体编排：AI 驱动的多智能体协作图 | **主线，设计→执行→回放闭环可用**，但能力边界明显 |

### 1.1 BPM 轨"不可用"的根因证据链（硬断点）

| # | 断点 | 证据 |
|---|------|------|
| B1 | **画布保存是假的**：保存按钮仅弹 toast，不调用任何 API；API 层甚至没有封装 graph 读写方法 | `WorkflowCanvas.tsx:475`；`gewu-web/src/lib/workflow.ts` 全文无 graph 方法（后端 `PUT /v1/workflows/{id}/graph` 存在但前端零调用） |
| B2 | **前后端节点模型全面错位**：前端节点目录 23 种（含触发器/HTTP/LLM/循环等愿景节点），后端引擎只识别 6 种（start/end/task/approval/condition/subprocess）；且画布目录**没有 start/end 节点**，即使保存成功，发起实例也会抛"工作流缺少起始节点" | `workflowTypes.ts:3-9` vs `WorkflowInstanceService.java:67-91` |
| B3 | **画布数据结构与后端 DTO 不匹配**：画布是 `{nodes:[{id,type,label,x,y,config}], connections:[{from,fromPort,to,toPort,label}]}`，后端期望 `SaveWorkflowGraphCommand{nodes:[{nodeId,nodeName,nodeType,config,positionX,positionY,sortOrder}], transitions:[{fromNodeId,toNodeId,conditionExpr,label,sortOrder}]}` | `WorkflowCanvas.tsx` vs `dto/SaveWorkflowGraphCommand` |
| B4 | **状态大小写错位**：前端比较 `i.status === 'RUNNING'/'SUSPENDED'/'COMPLETED'`，后端写小写 `'running'` 等，运行状态判定永远失真 | `WorkflowPage.tsx:44-46,169,412,492-493` vs `WorkflowInstanceService` |
| B5 | **流程无法被推进**：待办办理 UI 完全缺失——`completeNode`（办理/审批）、`listMyInstances`（我的实例）、通知接口在 `lib/workflow.ts` 有封装但**全站零调用**。流程发起后没有任何人能推进节点，是"死流程" | `WorkflowPage.tsx` 全文；`lib/workflow.ts:126,141,153` |

结论：BPM 轨断在"定义无法落库 → 发布无内容 → 发起报错 → 发起了也没人能办"的链路上，**每一环都断**。

### 1.2 BPM 轨后端引擎的真实程度

后端不是空壳，是**真实可跑的极简线性状态机**，但显著低于设计承诺：

| 能力 | 状态 | 证据 |
|---|---|---|
| 定义 CRUD / 发布 / 归档 | ⚠️ 真实但发布无任何校验（空图可发布） | `WorkflowService.java:102-111` |
| 画布图保存/回读 API | ⚠️ 存在但零校验，transition from/to 为空时按索引"臆造"链式连线 | `WorkflowService.java:137-168` |
| 实例启动/推进 | ✅ 真实：start 节点定位、transition 遍历、conditionExpr 求值、end 终态 | `WorkflowInstanceService.java:60-185,269-298` |
| 条件表达式 | ⚠️ 仅支持 `var op literal` 单比较（无 and/or），操作符子集 | `WorkflowInstanceService.java:300-330` |
| 审批驳回/回退 | ❌ 未实现（PRD F-033/BR-023）；approved 仅写入 variables | `CompleteNodeCommand` 消费点 `:159-161` |
| 并行分支 / 超时 / 子流程 | ❌ 未实现（同一时刻仅一个 running 节点） | `completeNode` 取 `runningNodes.get(0)` `:149` |
| 权限表 | ❌ `workflow_permission(_matrix)` 实体/Mapper 存在但**全仓零消费** | grep 零命中 |
| 通知 | ⚠️ 仅插 `workflow_notification` 表（无推送通道），前端无 UI | `WorkflowInstanceService.java:352-365` |
| 审计 | ✅ 落库但仅 START/COMPLETE/CANCEL；terminate 存在 beforeState 传参小 bug | `:208-213,367` |

### 1.3 编排引擎轨的能力边界（可用但受限）

编排轨经 46/47 两轮迭代后已闭环：可视化设计器（React Flow）、结构校验双闸（VL-01~12）、四编排模式、8 类节点（7 类可用）、同步/流式执行、暂停/恢复/取消、节点级执行记录与回放（FR-14）、HITL 审批中心。剩余边界：

| # | 缺口 | 证据 |
|---|------|------|
| O1 | **触发方式单一**：仅手动（列表页/设计器）与 REST API；无定时、无 Webhook、无事件触发、Agent 会话不能把编排图当工具调用 | 全库无编排调度器；`grep "implements Tool"` 零命中 |
| O2 | **版本管理薄弱**：版本固定 v1；激活单向（active 只读、无下架回草稿）；改图需删图重建且连带删执行历史 | `OrchestrationService` 图状态机；操作手册 §5.3 |
| O3 | **断点检查点仅内存**：进程重启后 PAUSED 执行无法恢复（resumable=false） | `OrchestrationEngine.java:74-79`；sprint3 完成报告技术债 |
| O4 | **SUBGRAPH 未实现**：引擎落 default 按 AGENT 处理，前端禁拖 | `PipelineModeHandler.java:198-206` |
| O5 | **executionMode 声明不生效**：REACT/PLAN_EXECUTE/REFLEXION/TOOL_PARALLEL 枚举与 UI 下拉存在，执行链不消费 | `grep executionMode mode/*.java` 零命中 |
| O6 | **节点级重试/超时缺失**：仅 LLM 客户端级重试（3 次）与 HUMAN 超时；AGENT/TOOL 节点无独立 retry/backoff/timeout 配置 | 47 号修复范围限定 |
| O7 | **HUMAN 节点审批配置占位**：refId（审批配置）无实体无消费，无法指定审批人/角色 | 46 号 §3.2 已确认 |
| O8 | **变量语法不一致**：校验规则 VL-09 识别 `${var.xxx}`，引擎渲染按整体 `${变量名}` 匹配——`${var.input}` 渲染为空串（手册 §8.3 已警告，属设计缺陷非用户错误） | `VariableTemplates.java` vs 前端校验 |

---

## 二、现状盘点：两轨能力与边界

### 2.1 决议沿革

| 文档/时间 | 决议 | 对本设计的影响 |
|---|---|---|
| 29 号 ADR-001 | 提出"统一编排模型消双轨"，将工作流引擎降级为编排图执行器后端 | 未落地，被后续决议覆盖 |
| **28 号 V1.2（2026-08-27）边界澄清** | **"两轨保留、不再合并"**：工作流=人工审批流（人推进），编排=Agent DAG（引擎自主推进） | **本设计遵循该决议**，在其框架内补全两轨各自的完整能力 |
| 46 号（2026-09-24） | 重申两轨边界，设计器只服务 orchestration | 前端文案区分"审批流"与"智能体编排" |
| 47 号（2026-09-24） | 编排可靠性四修复落地 | 编排域新需求以 47 为基线增量 |

### 2.2 能力矩阵（现状 → 目标）

| 能力维度 | BPM 审批流（现状） | 编排引擎（现状） | 边界归属裁定 |
|---|---|---|---|
| 图形化定义 | ❌ 画布不落库（B1） | ✅ React Flow 设计器 | 各自拥有画布；BPM 画布重做收敛节点 |
| 定义校验 | ❌ 零校验 | ✅ VL-01~12 双闸 | 各自校验，规则按域定义 |
| 发布/版本 | ⚠️ 发布无校验、无版本 | ⚠️ 激活单向、v1 固定 | 各自管理；编排补版本化（WFO-01） |
| 执行内核 | ✅ 极简线性状态机 | ✅ 四模式 DAG 引擎 | **自动执行能力归编排**；BPM 不再扩自动化节点 |
| 节点类型 | 6 种（后端） | 8 类（7 可用） | BPM 收敛为 6 种审批流节点；23 种愿景目录废弃 |
| 触发 | ⚠️ 仅手动发起实例 | ⚠️ 仅手动/API | 补齐：定时/Webhook/Agent 工具化（C 域） |
| 人工介入 | ❌ 待办 UI 缺失（B5） | ✅ HUMAN 节点 + 审批中心 | **审批中心作为共享底座**，两轨统一入口 |
| 通知 | ⚠️ 仅落库无 UI | ⚠️ SSE 事件（无站内通知沉淀） | 站内通知中心作为共享底座 |
| 执行历史/回放 | ⚠️ 实例列表（无节点时间线 UI） | ✅ 节点级记录 + 回放 | BPM 对齐补节点时间线（P2） |
| 权限 | ❌ 权限表零消费 | ⚠️ 沿用菜单/RBAC | BPM 接线办理人/审批人模型；不新建权限体系 |
| Agent/LLM 集成 | ❌ 无 | ✅ AGENT 节点/模型兜底 | AI 能力归编排；BPM 不加 LLM 节点 |

### 2.3 功能边界结论

1. **BPM 工作流 = "人驱动"的审批流**：节点由人办理推进，引擎只做路径与状态管理。收敛为 6 种节点，砍掉一切"自动化执行"愿景节点（HTTP/代码/LLM/数据库等）——这些能力在编排轨已存在或归属工具体系，双轨重复维护无收益。
2. **编排引擎 = "AI 驱动"的协作图**：补齐触发体系、版本化、可靠性；不承担"逐级人工签字"的组织流程语义（HUMAN 节点是"关卡"而非"岗位任务"）。
3. **共享底座（不重复建设）**：审批中心（两轨人工介入统一入口）、站内通知、工具目录、Agent 运行时、LLM 网关、RBAC、审计。
4. **互操作点（本期最小化）**：编排执行记录挂靠会话 sessionId 贯通；BPM↔编排互调（BPM 任务节点触发编排图、编排节点发起 BPM 审批流）列为开放问题，本期不做。

---

## 三、目标功能架构

### 3.1 分层架构（目标态）

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ 交互层    审批流设计器    编排设计器    审批中心(统一)    通知中心    运行监控    │
├─────────────────────────────────────────────────────────────────────────────┤
│ 触发层    BPM：手动发起                      编排：手动/API │ 定时 │ Webhook │  │
│                                              Agent 工具化调用（会话内触发图）   │
├─────────────────────────────────────────────────────────────────────────────┤
│ 定义层    WorkflowService（收敛6节点+校验+发布闸）  OrchestrationService        │
│           （图CRUD+VL校验+版本化+激活/下架）                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│ 执行层    WorkflowInstanceService         OrchestrationEngine(Orchestrator)   │
│           线性状态机+条件边               四模式DAG+PLAN/SUBGRAPH+HITL          │
│           办理/审批推进                   检查点持久化+节点级重试/超时            │
├─────────────────────────────────────────────────────────────────────────────┤
│ 共享底座  审批中心(两轨) │ 站内通知(SSE+落库) │ 工具目录 │ Agent运行时 │ LLM网关 │
│           RBAC+审计 │ 可观测(OTel/指标) │ 沙箱/MCP                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 两轨边界（修订 28 号边界表）

| 维度 | BPM 工作流（目标态） | 编排引擎（目标态） |
|---|---|---|
| 推进方式 | 人在"我的待办"办理/审批推进 | 引擎自主推进 + HUMAN 关卡 |
| 节点模型 | start/end/task/approval/condition/subprocess（6 种，收敛定稿） | 8 类（SUBGRAPH 本期实现） |
| 定义存储 | workflow + workflow_node + workflow_transition（结构化三表） | orchestration_graph（JSON 原文 + 版本表） |
| 发布语义 | 发布闸：结构校验通过才可发布，发布后只读 | 版本化：激活=发布新版本，支持下架/回滚（WFO-01） |
| 触发 | 手动发起实例（填变量） | 手动/API/定时/Webhook/Agent 工具化 |
| 人工介入 | 待办任务（task 办理 + approval 审批，含驳回分支） | HUMAN 节点 → 审批中心 |
| 通知 | 任务创建/审批请求/完成 → 站内通知中心 | 审批请求 → 站内通知中心 |
| 前端入口 | 「工作流」（文案：审批流） | 「编排引擎」（文案：智能体编排） |

---

## 四、关键设计决策

| # | 决策 | 备选方案 | 结论与理由 |
|---|---|---|---|
| D1 | **两轨边界维持 28-V1.2 决议，BPM 收敛为纯审批流** | a) 推翻决议统一到编排（29-ADR-001）；b) 两轨都按 23 节点愿景扩展 | 选收敛。a) 推翻决议需重做审批中心归属与 7 张表迁移，风险大收益不明确；b) 与编排轨能力重复，双倍维护。BPM 后端 6 节点状态机已真实可跑，补齐闭环成本最低 |
| D2 | **BPM 画布基于 @xyflow/react 重做** | a) 修补现有 SVG 自绘画布；b) 表单式/JSON 配置无画布 | 选重做。46 号已评估自绘方案"补齐持久化/撤销/运行态/性能成本高、天花板低"；编排设计器组件族（designer/ 子组件）已验证 React Flow 落地路径，可复用转换层与校验面板模式。b) 丧失 46 号固化的四区交互范式 |
| D3 | **编排图版本化采用"版本表 + 快照"方案** | a) 维持单版本激活单向；b) 状态列回退（active→draft） | 选版本表。b) 会破坏"执行绑定的定义快照不可变"语义；a) 使改图必须删图重建（连带删执行历史），是当前最大使用痛点。版本表方案：每次激活产生不可变版本快照，执行记录绑定 versionId，草稿始终可编辑 |
| D4 | **executionMode 处置：本期从 UI 移除，接线列为 P2** | a) 本期接线 PlanExecute/Reflexion 运行时；b) 保留 UI 标注"暂未生效" | 选移除+P2。UI 暴露无效字段违反 46 号 R5 教训（防误导）；接线涉及三个 runtime 类的行为验证与测试，编排域 P0/P1 需求优先 |
| D5 | **编排检查点持久化到 DB** | a) 维持内存注册表；b) 复用 execution.variables 存检查点 JSON | 选独立表 `orchestration_checkpoint`。a) 进程重启丢检查点是 O3 硬伤；b) 语义混杂（variables 列还承担取消终态快照职责），独立表可对检查点做生命周期管理（恢复成功即删） |
| D6 | **Agent 工具化调用编排图：动态工具注册方案** | a) 固定一个 `run_orchestration_graph` 通用工具（参数传 graphId）；b) 每个激活图注册为独立工具 | 选 a) 通用工具 + 白名单。b) 工具数量随图增长污染 ReAct 工具列表；a) 单工具 + graphId 参数 + 入参校验（图存在且 active），Agent 可从会话内触发流程，这是两轨（会话×编排）最重要的集成点 |
| D7 | **定时触发用 Spring @Scheduled + DB 配置表，不引入分布式调度器** | a) Quartz 集群；b) RocketMQ 延迟消息 | 选 @Scheduled + DB 行 + 应用内防重（DragonflyDB SETNX）。当前单体部署（资料总览 §2.1），多实例部署时升级 Quartz 为开放问题，不为单体引入新中间件 |
| D8 | **审批中心统一两轨** | a) BPM 自建待办页 | 选统一。BPM 的 approval 节点本质与编排 HUMAN 节点同为"人裁决"动作，审批中心扩展一个"审批流任务"数据源即可，用户单入口 |

---

## 五、功能需求设计

编号体系：`WF-<域><序号>`；域代码 A=审批流（BPM）、O=编排（Orchestration）、C=触发与协同。与既有编号的映射随条目标注（PRD F-0xx / 46 号 FR-xx）。

### 5.1 A 域：BPM 审批流可用性闭环（P0）

> 目标：把"工作流"页从演示态修复为完整可用闭环——**设计（落库）→ 发布（有闸）→ 发起（有表单）→ 办理（有待办）→ 归档（有历史）**。

| 编号 | 需求 | 优先级 | 需求描述与验收标准 | 对应旧编号 |
|---|---|---|---|---|
| WFA-01 | 节点模型收敛 | P0 | 前端节点目录收敛为 6 种：start（发起）、end（结束）、task（人工办理）、approval（审批，通过/驳回双出口）、condition（自动条件）、subprocess（占位禁拖，标注未实现）。废弃 23 种愿景目录。验收：画布只能拖入 6 种节点；节点类型枚举与后端 `node_type` 一一对应 | PRD F-026 |
| WFA-02 | 画布真实保存 | P0 | 基于 React Flow 重做审批流画布；保存调用 `PUT /v1/workflows/{id}/graph`，请求体按 `SaveWorkflowGraphCommand` 结构组装（nodeId/nodeName/nodeType/config/positionX/positionY/sortOrder + transitions）；打开工作流时 `GET .../graph` 回填画布。验收：保存后刷新页面画布内容完整还原；DB 中 nodes/transitions 行数与画布一致 | B1/B3 |
| WFA-03 | 定义结构校验（前端实时 + 后端双闸） | P0 | 新增 BPM 图校验规则 WV-01~08（见 §5.4），前端画布实时提示（校验面板复用编排设计器模式），后端保存与发布前双闸。验收：缺 start/缺 end/悬空边/环/condition 节点出边无条件表达式，均被 ERROR 阻断并可点击定位 | 对标编排 VL 体系 |
| WFA-04 | 发布闸 | P0 | 发布前执行结构校验，ERROR 阻断并返回问题清单；发布后定义只读（维持 BR-018）。验收：含错误的图发布被拒且提示可读；空图发布被拒 | 修复 `WorkflowService.publishWorkflow` 无校验 |
| WFA-05 | 运行状态修复 | P0 | 统一状态字面量：后端收敛用 `WorkflowStatus` 枚举 code（小写），前端状态判定与徽标改用小写常量（或统一走映射函数）。验收：实例运行中/暂停/完成状态显示正确，暂停/终止按钮可用性正确 | B4 |
| WFA-06 | 发起实例表单 | P0 | 发起实例时弹出变量表单：动态渲染该图定义中节点引用的变量键（或手工 KV 输入），提交 `startInstance(workflowId, { title, variables })`。验收：发起后实例 variables 落库；condition 节点可依据 variables 求值分流 | BR-022 |
| WFA-07 | 我的待办与任务办理 | P0 | 新增"我的待办"视图（列表页 Tab 或独立区块）：聚合 `listMyInstances` 中 status=running 且当前节点 assignee=当前用户的实例；提供两种办理动作——task 节点「提交办理」（completeNode 带 output JSON）、approval 节点「通过/驳回」（completeNode 带 approved + comment）。验收：A 发起含 approval 节点的流程，B 在待办中审批，流程推进到下一节点；全程无 API 直连 | B5、PRD F-032 |
| WFA-08 | 审批驳回语义 | P0 | approval 节点驳回时：向实例 variables 写入 `approved=false` + 驳回意见；引擎按该节点出边中条件表达式（如 `var:approved == true` / `var:approved != true`）路由，支持"驳回走修复分支"与"驳回终止（连到 end）"两种画法；提供画布快捷模板。验收：驳回后流程按条件边推进或终止，符合 BR-023 的可配置回退语义（回退到指定前驱节点以"驳回目标节点"边表达，引擎沿边走即可，不新增回退原语） | PRD F-033/BR-023（简化落地版） |
| WFA-09 | 通知中心接入 | P1 | 节点分配（TASK_CREATED/APPROVAL_REQUIRED）与流程完成/终止时写通知并经 SSE 推送站内通知（复用既有 SSE 通道与通知数据表）；前端通知入口展示未读数与列表、可标记已读。验收：发起实例后办理人收到站内通知；审批完成后发起人收到结果通知 | PRD US-WF-03 |
| WFA-10 | 办理人模型增强 | P1 | approval/task 节点 config 支持 `assigneeId`（已有）扩展 `assigneeRole`（按角色圈定候选人，任一候选人可办理）；办理权限校验：仅 assignee 本人（或角色成员）可 completeNode，越权 403。验收：无权限用户办理被拒；角色指派时角色内用户可见待办 | workflow_permission 消费的最小闭环 |

### 5.2 O 域：编排引擎能力补齐（P1/P2）

> 目标：在编排轨已闭环基础上补齐"长期可用"所需的生产化能力。47 号修复为基线，不重复。

| 编号 | 需求 | 优先级 | 需求描述与验收标准 | 对应旧编号 |
|---|---|---|---|---|
| WFO-01 | 图版本化 | P1 | 新增 `orchestration_graph_version` 表：每次激活（激活或再激活）将当前 graph_definition 快照为不可变版本（version 自增）；执行记录绑定 versionId；执行加载定义时优先取版本快照。草稿始终可编辑；active 图出现"编辑新版本"入口（改草稿→再激活产生 v2）。验收：v1 执行回放不受 v2 激活影响；同一图可查版本列表与每版定义 diff（前端展示 JSON diff 即可） | 46 号开放问题 1 |
| WFO-02 | 激活下架/版本回滚 | P1 | active 图支持"下架"（active→draft，进入可编辑状态，已有执行历史保留）与"回滚到历史版本"（将指定版本快照写回草稿）。验收：下架后列表执行按钮禁用；回滚后画布内容与所选版本一致 | 修复操作手册 §5.3 痛点 |
| WFO-03 | 检查点持久化 | P1 | 新增 `orchestration_checkpoint` 表（executionId、graphSnapshot、variables、resumeFromNode、createdAt）；暂停时落库、恢复成功后删除；恢复接口优先读 DB 检查点。验收：暂停后重启服务进程，恢复接口返回 resumable=true 且断点续跑成功跳过已完成节点 | O3 |
| WFO-04 | SUBGRAPH 嵌套子图 | P1 | 实现 SUBGRAPH 节点：refId 指向另一张 active 图（禁止自引用/成环，校验 VL-13）；执行时加载子图定义、以父上下文变量为初始变量独立执行，子图最终输出写入父上下文（变量名=节点 ID）；深度上限 2。前端解除置灰并支持 refId 下拉。验收：父子图两级嵌套执行成功，父图 MERGE 能汇聚子图产出 | O4、46 号 FR-13 |
| WFO-05 | 节点级重试与超时 | P1 | AGENT/TOOL 节点 config 新增 `retryCount`（默认 0，上限 3）、`retryBackoffMs`（默认 1000）、`timeoutSeconds`（默认继承引擎级）；超时按失败处理并受 continueOnFailure 语义约束；节点执行记录记录重试次数。验收：配置 retry 的节点在 LLM 瞬时失败后自动重试成功；timeout 触发后按失败路径终止或跳过 | O6、47 号延伸 |
| WFO-06 | 变量语法对齐 | P2 | 统一变量引用语法为 `${name}`：VL-09 校验对象改为无前缀写法；引擎渲染与校验同源（同一可解析变量集合：图变量 + input + 已完成节点产出）；`${var.xxx}` 兼容期自动降级为 `${xxx}` 并告警。验收：设计器提示、校验、渲染三处行为一致；存量 `${var.input}` 图不再渲染为空串 | O8 |
| WFO-07 | HUMAN 节点审批配置 | P2 | HUMAN 节点 config 新增 `assigneeId`/`assigneeRole`（审批人圈定，审批中心按此展示待办人）；审批中心编排 Tab 按 assignee 过滤。验收：指定审批人的审批请求仅对其可见可操作；未指定时维持现状（全员可见） | O7 |
| WFO-08 | executionMode 收敛 | P2 | 本期从设计器属性面板移除 executionMode 下拉（D4）；P2 接线 PLAN_EXECUTE（复用 runtime/PlanExecuteRuntime）后恢复并标注生效。验收：UI 无无效字段误导；接线后 PLAN_EXECUTE 节点行为与 REACT 可区分并有测试 | O5、D4 |

### 5.3 C 域：触发体系与协同（P1/P2）

| 编号 | 需求 | 优先级 | 需求描述与验收标准 |
|---|---|---|---|
| WFC-01 | Agent 工具化调用编排图 | P1 | 注册通用工具 `run_orchestration_graph`（描述、参数 schema：graphId 必填 + input 可选）；执行前校验图存在且 active、调用者对图有可见权限；工具内同步执行（带超时）并把最终输出与执行 ID 返回给 Agent；每次调用写编排执行记录（session_id 贯通会话）。验收：会话中 Agent 依据用户指令自主调用该工具并汇报执行结果；执行历史可见该次记录 |
| WFC-02 | 定时触发 | P2 | 编排图新增触发配置（图级 `schedule`：cron + 时区 + 入参模板 + 启停开关）；调度器每分钟扫描到期任务（@Scheduled + DB 行 + SETNX 防重），以系统身份发起流式执行并落执行记录（triggerType=SCHEDULE）。验收：配置每小时触发的图按期产生执行记录；停用开关生效 |
| WFC-03 | Webhook 触发 | P2 | 网关开放 `POST /api/v1/orchestration/webhooks/{token}` 匿名端点（token 图级生成、可重置）；body 整体作为执行输入（triggerType=WEBHOOK），可配置鉴权头校验；命中即触发异步执行。验收：外部 curl 携带 token 触发一次执行并有记录；错误 token 返回 404（不暴露存在性） |
| WFC-04 | 执行触发类型可观测 | P2 | 执行记录与列表页增加 triggerType 字段（MANUAL/API/AGENT_TOOL/SCHEDULE/WEBHOOK）与来源信息展示；统计卡片按触发类型分列。验收：四类触发产生的执行可区分追溯 |

### 5.4 校验规则与编排守恒约束

**BPM 校验规则（WV，新增，双闸）**：

| 编号 | 级别 | 规则 |
|---|---|---|
| WV-01 | ERROR | 存在且仅存在 1 个 start 节点 |
| WV-02 | ERROR | 存在至少 1 个 end 节点 |
| WV-03 | ERROR | nodeId 唯一非空；边 from/to 引用存在 |
| WV-04 | ERROR | 图无环（start 可达性 + 拓扑检测） |
| WV-05 | ERROR | condition 节点出边必须带 conditionExpr；approval 节点通过/驳回出边必须有条件表达式或使用默认模板 |
| WV-06 | WARNING | 孤立节点（无任何边引用） |
| WV-07 | WARNING | approval 驳回边未连到任何节点（默认终止） |
| WV-08 | WARNING | 表达式引用变量未在实例变量或上游产出中声明 |

**编排新增校验**：VL-13（SUBGRAPH：refId 必须指向存在且 active 的图、禁止自引用与跨图环，深度 ≤2）；VL-09 重定义（见 WFO-06）。

**守恒约束（两轨通用红线）**：执行中的定义快照不可变；删除定义时级联清理执行与审批数据前必须二次确认（BPM 当前删除无二次确认，随 WFA-02 补齐交互）。

### 5.5 非功能需求

| 编号 | 需求 |
|---|---|
| NFR-01 | 主题一致：全部新增前端颜色走 CSS 变量（四主题可切换），禁硬编码（46 号 NFR-01 延续） |
| NFR-02 | 内网部署：React Flow 等依赖随包构建，零 CDN 外链 |
| NFR-03 | 性能：审批流画布 ≥50 节点流畅；编排画布维持 ≥100 节点（46 号 NFR-03） |
| NFR-04 | 兼容：存量 BPM 工作流（无图内容）打开画布为空图兜底 + 引导创建 start；存量编排图 100% 可打开（坐标缺失自动布局兜底） |
| NFR-05 | 权限：不新增权限模型，沿用菜单/RBAC + 办理人/审批人资源级校验 |
| NFR-06 | 安全：Webhook token 强随机 + SM3 存储哈希；办理/审批越权 403；表达式求值维持白名单解析器（禁止脚本语言注入） |
| NFR-07 | 审计：BPM 补 REVIEW/REJECT 审计动作；修复 terminate 审计 beforeState 传参错误 |

---

## 六、数据模型设计

### 6.1 新增表

```sql
-- WFO-01：编排图版本
CREATE TABLE orchestration_graph_version (
  id            VARCHAR(26) PRIMARY KEY,
  graph_id      VARCHAR(26) NOT NULL,
  version       INT NOT NULL,
  graph_definition LONGTEXT NOT NULL,        -- 不可变快照
  mode          VARCHAR(32),
  activated_by  VARCHAR(26),
  activated_at  BIGINT,
  UNIQUE KEY uk_graph_version (graph_id, version)
);

-- WFO-03：编排检查点（恢复成功即删）
CREATE TABLE orchestration_checkpoint (
  id             VARCHAR(26) PRIMARY KEY,
  execution_id   VARCHAR(26) NOT NULL,
  graph_snapshot LONGTEXT NOT NULL,
  variables      LONGTEXT,
  resume_from_node VARCHAR(64),
  created_at     BIGINT,
  UNIQUE KEY uk_execution (execution_id)
);

-- WFC-02：编排定时触发配置
CREATE TABLE orchestration_schedule (
  id           VARCHAR(26) PRIMARY KEY,
  graph_id     VARCHAR(26) NOT NULL,
  cron_expr    VARCHAR(64) NOT NULL,
  timezone     VARCHAR(64) DEFAULT 'Asia/Shanghai',
  input_template LONGTEXT,
  enabled      TINYINT DEFAULT 1,
  last_fire_at BIGINT,
  next_fire_at BIGINT
);

-- WFC-03：编排 Webhook 配置
CREATE TABLE orchestration_webhook (
  id           VARCHAR(26) PRIMARY KEY,
  graph_id     VARCHAR(26) NOT NULL,
  token_hash   VARCHAR(128) NOT NULL,       -- SM3(token)
  header_check VARCHAR(512),
  enabled      TINYINT DEFAULT 1,
  created_at   BIGINT,
  UNIQUE KEY uk_token (token_hash)
);
```

### 6.2 变更表

| 表 | 变更 | 对应需求 |
|---|---|---|
| orchestration_execution | + `version_id`、`trigger_type`（MANUAL/API/AGENT_TOOL/SCHEDULE/WEBHOOK）、`trigger_source` | WFO-01/WFC-04 |
| orchestration_node_execution | + `retry_count` | WFO-05 |
| workflow_node | config 约定扩展：`assigneeRole`；approval 节点默认条件模板 | WFA-08/10 |
| workflow_instance | 无结构变更（variables 复用）；+ 索引 `(assignee 现值走 node_instance)` | WFA-07 |
| workflow_audit_log | 无结构变更；动作枚举补 REVIEW/REJECT | NFR-07 |

Flyway 迁移：V49 起（V26~V48 已占用），H2 测试 schema 同步。

### 6.3 不动项

BPM 的 7 张表结构维持（workflow_permission 本期仅消费不重构）；23 种前端愿景节点不入库（从未落库，无迁移负担）。

---

## 七、API 设计

### 7.1 BPM 侧（`/api/v1/workflows`）

| 方法 | 路径 | 变更 | 需求 |
|---|---|---|---|
| PUT | `/{workflowId}/graph` | 行为变更：保存前执行 WV 校验（ERROR 阻断），返回结构化问题清单 | WFA-03 |
| POST | `/{workflowId}/publish` | 行为变更：发布前执行 WV 校验 | WFA-04 |
| POST | `/{workflowId}/instances` | 已有 startInstance，补 request 校验注解；响应补当前节点信息 | WFA-06 |
| GET | `/instances/my` | 语义明确化：返回"与我相关（发起人/当前办理人）"的运行中实例 + 当前节点 + 可执行动作 | WFA-07 |
| POST | `/instances/{instanceId}/nodes/{nodeInstanceId}/complete` | 已有 completeNode，补：办理权限校验（assignee 本人/角色成员）；approval 语义（approved=false 时写 variables 并按条件边路由）；审计动作 REVIEW/REJECT | WFA-07/08 |
| GET | `/instances/notifications` + PUT `/{id}/read` | 已有，补 SSE 推送 | WFA-09 |

### 7.2 编排侧（`/api/v1/orchestration`）

| 方法 | 路径 | 说明 | 需求 |
|---|---|---|---|
| PUT | `/graphs/{graphId}/deactivate` | 新增：下架 active→draft | WFO-02 |
| GET | `/graphs/{graphId}/versions` | 新增：版本列表 | WFO-01 |
| POST | `/graphs/{graphId}/versions/{versionId}/rollback` | 新增：版本快照写回草稿 | WFO-02 |
| GET | `/executions/{executionId}` | 响应补 versionId/triggerType | WFO-01/WFC-04 |
| PUT | `/graphs/{graphId}/schedule` | 新增：定时配置（cron 校验 + 下次触发时间预览） | WFC-02 |
| PUT | `/graphs/{graphId}/webhook` | 新增：生成/重置/停用 webhook token（token 仅创建响应返回一次） | WFC-03 |
| POST | `/webhooks/{token}` | 新增：匿名触发端点（网关放行白名单） | WFC-03 |

### 7.3 会话侧（Agent 工具）

`run_orchestration_graph` 注册进 ReAct 工具注册表（`@ToolProvider` 或等价机制），工具定义包含参数 schema 与安全描述；不新增 REST 端点（走工具执行链）。

---

## 八、前端设计

### 8.1 页面与组件

| 模块 | 内容 | 复用 |
|---|---|---|
| 审批流设计器（新 `WorkflowDesigner.tsx`） | React Flow 画布：6 节点库、审批节点双出口（通过/驳回）、condition 表达式编辑器（下拉操作符 + 变量提示）、保存/发布/校验面板、未配置角标 | 编排 designer 组件族模式（Palette/NodeCard/PropertyPanel/校验面板）；`flowToDefinition` 转换层思想 |
| 工作流列表页改造 | 状态徽标修复（WFA-05）；发起实例变量表单弹窗（WFA-06）；删除二次确认；进入新设计器 | 现有 WorkflowPage 骨架保留 |
| 我的待办（新 Tab/区块） | 待办列表（按超时升序）→ 办理抽屉：节点信息、变量上下文、task 提交（output JSON 编辑）、approval 通过/驳回（意见必填） | 审批中心交互范式 |
| 通知中心 | 通知列表 + 未读数 + 已读标记；SSE 订阅增量 | 既有 SSE 通道 |
| 编排设计器增量 | 版本历史面板（列表/diff/回滚/下架入口）；SUBGRAPH 节点启用 + 子图 refId 下拉；节点重试/超时字段；executionMode 移除；触发配置（定时/Webhook）入口 | 现有 designer 组件族 |
| 审批中心扩展 | 数据源扩展"审批流任务"（BPM approval 待办），与编排 HUMAN 审批并列展示 | 现有审批中心页 |

### 8.2 交互约束

- 两轨文案严格区分：「工作流」页头部标注"审批流"；「编排引擎」页标注"智能体编排"（46 号 R3）。
- 危险操作（删除工作流/编排图、下架、回滚版本）一律二次确认，明示连带影响。
- 发布/激活按钮在校验 ERROR 时禁用并展示问题数徽标。

---

## 九、兼容性与迁移

| 项 | 策略 |
|---|---|
| 存量 BPM 工作流（无图） | 打开设计器为空图 + 引导；已有实例继续按其 variables 展示历史（不重放） |
| 存量编排图 | 版本化上线时做一次性迁移：现存 active 图的当前定义写入 version 1 快照；无 mode 字段的沿用既有回填规则（47 号已处理） |
| `${var.xxx}` 存量写法 | WFO-06 兼容期自动降级转换 + 告警日志，不做破坏性变更 |
| admin-web 死常量 | 顺手清理 `gewu-admin-web/src/lib/api.ts:21-24`（WORKFLOW_RUN/STATUS 后端不存在的死配置） |
| 前端 23 种节点目录 | 直接废弃替换，无迁移（从未持久化） |

---

## 十、风险与开放问题

### 10.1 风险

| # | 风险 | 等级 | 缓解 |
|---|---|---|---|
| R1 | BPM 画布重做引入回归（列表页已有发布/归档/终止功能） | 中 | 保留 WorkflowPage 列表骨架只换画布区；设计器独立组件灰度切换 |
| R2 | approval 驳回语义（WFA-08）与 PRD BR-023"回退"存在解释差 | 中 | 本期以"条件边路由"实现可配置驳回路径（含回退到前驱的画法），文档明示；真正的原语级 rollback 节点列为开放问题 |
| R3 | 版本化迁移改变执行取定义的代码路径 | 中 | 迁移脚本幂等；执行加载路径加版本快照命中失败时回退 graph_definition 的兜底 |
| R4 | SUBGRAPH 引擎改动触碰 PipelineModeHandler 核心 | 中 | 独立 handler 方法 + 深度上限 + 环校验 VL-13；补充单测覆盖嵌套/环/失败传播 |
| R5 | Agent 工具化调用放大编排执行量（预算风险） | 中 | 工具侧单次执行预算上限（Token/时长）+ 会话级频控 |
| R6 | 定时触发多实例部署重复执行 | 低 | 单体现状 + SETNX 防重；K8s 多副本前升级为开放问题跟踪 |

### 10.2 开放问题（需拍板）

1. BPM 原语级"回退到指定节点"（BR-023 完整版）是否需要？当前条件边画法可覆盖多数场景。
2. BPM 子流程（subprocess）是否排期？建议与编排 SUBGRAPH（WFO-04）分开评估。
3. BPM↔编排互操作（BPM 任务节点触发编排图 / 编排节点发起审批流）方向与优先级。
4. 多实例部署时间表（决定定时触发是否提前引入 Quartz 集群）。
5. 通知渠道扩展（邮件/企微）排期——本期仅站内。

---

## 附录 A：需求-证据追溯表（关键项）

| 需求 | 现状证据 |
|---|---|
| WFA-01/02 | `workflowTypes.ts:3-9`（23 节点）；`WorkflowCanvas.tsx:475`（假保存）；`WorkflowService.java:137-168`（无校验落库） |
| WFA-05 | `WorkflowPage.tsx:44-46`（'RUNNING' 大写比较） |
| WFA-07 | `lib/workflow.ts:126,141,153`（零调用封装） |
| WFO-01/02 | 操作手册 §5.3（激活单向）；`OrchestrationService` 图状态机 |
| WFO-03 | `OrchestrationEngine.java:74-79`（内存检查点）；sprint3-done 技术债清单 |
| WFO-04 | `PipelineModeHandler.java:198-206`（default 按 AGENT） |
| WFO-05 | 47 号修复范围；`LlmClientRegistry` 仅客户端级重试 |
| WFC-01 | 全库无编排相关 Tool 实现（grep 零命中） |

---

*本文档基于代码勘察与既有设计文档（28/29/30/31/46/47、PRD-SRS）交叉验证编写。实施计划见 `docs/plan/exe_plan/workflow-redesign-implementation-plan-2026-09.md`。*
