# Veloflow 节点体系 v2 · 对标 BPMN 2.0/n8n 的补全设计

> 编号：53-node-catalog-v2
> 版本：V1.0 · 2026-09-27
> 性质：节点体系设计定稿（取代 51 号 §二的 26 类目录；51 号其余章节继续有效）
> 触发背景：设计器节点库评审（用户）——当前节点未对标主流流程引擎的常规流程节点，
> 最突出的是**人工任务族整体缺失**（BPM 引擎的差异化核心节点不在节点库中）
> 关联：51（引擎设计）、52（产品化）、PRD F-026（8 类节点）、28-V1.2 边界

---

## 一、对标矩阵：BPMN 2.0 核心元素 × Veloflow

对标对象：Camunda/Flowable 的 BPMN 2.0 元素体系（治理型）与 n8n/Dify（自动化型）。逐元素核查：

### 1.1 事件（Events）

| BPMN 元素 | n8n/Dify 对应 | Veloflow 现状 | 裁定 |
|---|---|---|---|
| Start Event (None) | Manual Trigger | ✅ manual-trigger | 保留 |
| Start Event (Timer) | Schedule Trigger | ✅ schedule-trigger | 保留 |
| Start Event (Message/Webhook) | Webhook | ✅ webhook-trigger | 保留 |
| Start Event (Signal) | 事件触发 | ✅ event-trigger | 保留 |
| End Event (None) | return | ✅ return | 保留 |
| **End Event (Error)** | Error 节点 | ❌ 无错误终态表达 | **新增 `error-end`** |
| **End Event (Terminate)** | — | ❌ 无法"终止所有在途分支" | **新增 `terminate-end`** |
| Boundary Event (Timer) | approval timeout | ⚠️ 以 approval config 形态存在（简化可接受） | 文档注明等价关系 |
| Boundary Event (Error) | onError 分支 | ⚠️ onError goto 已设计（51 号 §八），前端无表达 | P2 随任务治理补前端 |
| Intermediate Catch (Message) | Wait for webhook | ❌ 无法在流程中段等待外部消息 | **新增 `receive-message`** |
| Intermediate Throw (Message) | Respond to Webhook | ❌ webhook 触发的流程无法同步返回中间结果 | **新增 `respond`** |
| Intermediate Catch (Signal) | — | ❌ | **新增 `event-wait`**（随 P3 事件体系） |

### 1.2 任务（Tasks）——最大缺口

| BPMN 元素 | n8n/Dify 对应 | Veloflow 现状 | 裁定 |
|---|---|---|---|
| **User Task（人工任务）** | （n8n 无——BPM 专属） | ❌ **前端目录缺失**；后端 task 语义未落地（P2） | **新增 `task`（P2 核心）** |
| **审批（User Task 变体，会签/或签）** | — | ❌ 同上（P2 已规划后端，前端目录无） | **新增 `approval`（P2 核心）** |
| Service Task | HTTP/Code | ✅ http-request / code | 保留 |
| Script Task | Code | ✅ code（P4 落地） | 保留 |
| **Business Rule Task（决策表）** | — | ❌ 决策逻辑只能用 switch 逐条硬编码 | **新增 `decision`（决策表节点）** |
| Send Task | Email/IM | ✅ email / im-notify | 保留 |
| Receive Task | — | ❌ 同 Intermediate Catch (Message) | 由 `receive-message` 覆盖 |
| **Manual Task** | — | —（无自动化动作的纯人工知会） | 并入 task（manual 标志），不单列 |
| Call Activity | Sub-workflow | ✅ sub-workflow（P3 落地） | 保留 |

### 1.3 网关（Gateways）

| BPMN 元素 | n8n/Dify 对应 | Veloflow 现状 | 裁定 |
|---|---|---|---|
| Exclusive Gateway (XOR) | IF / Switch | ✅ condition / switch | 保留（condition=XOR+表达式，switch=XOR+值匹配） |
| **Parallel Gateway (AND 分叉)** | — | ⚠️ 后端 parallel 已实现，**前端目录缺失** | **前端目录补 `parallel`** |
| **Parallel Gateway Join (AND 汇聚)** | Merge | ⚠️ 后端 join 已实现（ALL 策略） | **前端目录补 `join`** |
| **Inclusive Gateway (OR)** | — | ⚠️ 已并入 join 策略 N_OF_M（joinCount） | 文档澄清，不单列节点（配置形态） |
| Event Gateway | — | ❌ | **新增 `event-wait`**（见 1.1，等待多事件之一） |
| Complex Gateway | — | — | 不做（BPMN 中亦极少用） |

### 1.4 数据与其他

| 能力 | Veloflow 现状 | 裁定 |
|---|---|---|
| Multi-Instance（串行/并行多实例，会签/循环的 BPMN 标准形态） | loop 串行已设计；**并行多实例缺** | loop 增加 `parallelMode` config（P2/P3） |
| Data Mapping | transform ✓ | 保留 |
| Compensation（补偿） | — | 远期（P5+，真实需求出现再立项） |
| Error Workflow（n8n 专属：失败时调起另一工作流） | onError goto 已覆盖单流程内 | 流程级 error-workflow 列 P3+ 可选 |
| Sticky Note（设计器便签） | — | P5 设计器 UX |

---

## 二、缺口汇总

| # | 缺口 | 性质 | 严重度 |
|---|---|---|---|
| G1 | **人工任务族（task/approval）前端目录缺失** | BPM 差异化核心节点完全不可见 | **高**（P2 落地即补） |
| G2 | 端点事件无分化（error-end/terminate-end） | 失败分支表达与多分支终止能力缺失 | 中 |
| G3 | parallel/join 前端目录缺失（仅后端） | 并行编排对用户不可见 | 中 |
| G4 | 消息中间形态缺失（receive-message/respond/event-wait） | 外部系统同步交互/事件驱动推进缺失 | 中（P3） |
| G5 | 决策表节点缺失（decision） | 多条件决策表达繁琐 | 低（P2+） |
| G6 | 并行多实例缺失（loop parallelMode） | 并行 foreach 场景 | 低（P2/P3） |

---

## 三、节点体系 v2 定稿（7 大类 · 34 种）

在 26 类基础上**新增 8 类**（task/approval/error-end/terminate-end/decision/receive-message/respond/event-wait），分组重组如下。新增项标注 ★。

### 3.1 触发器（5）— 流程入口

| type | 名称 | 关键 config | 状态 |
|---|---|---|---|
| manual-trigger | 手动触发 | formFields? | ✅ |
| schedule-trigger | 定时触发 | cron、timezone、inputTemplate | ✅ 设计（P3 落地） |
| webhook-trigger | Webhook | path、authHeader? | ✅ 设计（P3 落地） |
| event-trigger | 事件触发 | eventType | ✅ 设计（P3 落地） |
| upstream-trigger | 上游触发 | — | ✅ 设计（P3 落地） |

### 3.2 人工任务（2）★ 新分组——BPM 差异化核心

| type | 名称 | 关键 config | 状态 |
|---|---|---|---|
| task | 人工办理 | assigneeId/assigneeRole、formFields?、timeoutHours、timeoutAction、rejectTargetNodeId? | ★ 新增（P2 核心） |
| approval | 审批 | approverIds[]/approverRoles[]、**approvalMode（ANY/ALL/RATIO）**、timeoutHours、timeoutAction、rejectTargetNodeId? | ★ 新增（P2 核心） |

> task/approval 的完整治理语义（会签或签/超时升级/驳回回退/委托）见 51 号 §七——本目录将其提升为**一级节点分组**，与 Camunda User Task 对位。

### 3.3 逻辑控制（7）

| type | 名称 | 端口 | 状态 |
|---|---|---|---|
| condition | 条件判断 | true/false | ✅ P1 已落地 |
| switch | 多路分支 | 各 case/default | ✅ P1 已落地 |
| loop | 循环遍历 | item/done（+parallelMode ★） | ✅ P1 已落地；★ 并行多实例 P2/P3 |
| parallel | 并行网关 | 多出边 | ★ 前端目录补（后端 P1 已落地） |
| join | 汇聚网关 | 单出 | ★ 前端目录补（后端 P1 已落地，策略 ALL/FIRST/N_OF_M） |
| filter | 门控过滤 | pass/block | ✅ P4 落地 |
| **decision** | 决策表 ★ | 单出 | ★ 新增：条件→结果映射表（行式规则，命中首行输出），替代多级 switch 嵌套；P2+ 落地 |

### 3.4 AI 能力（4）

| type | 名称 | 状态 |
|---|---|---|
| llm | 大模型调用 | ✅ 设计（P4 落地） |
| knowledge | 知识检索 | ✅ 设计（P4 落地） |
| agent | 智能体 | ✅ 设计（P4 落地） |
| orchestration | 编排图 | ✅ 设计（P4 落地） |

### 3.5 数据处理（3）

transform / json-parse / set-variable——✅ P1 已落地。

### 3.6 集成对接（4）

http-request / database（P1 仅只读） / email / im-notify——http-request ✅ P1 已落地，其余分期。

### 3.7 流程控制与事件（8）

| type | 名称 | 语义 | 状态 |
|---|---|---|---|
| delay | 延时等待 | 等待型，定时器驱动 | ✅ P1 已落地 |
| sub-workflow | 子工作流 | 等待型，子实例完成驱动 | ✅ 设计（P3） |
| counter | 计数器 | 计数与重置 | ✅ 目录保留（P2 可选） |
| return | 返回结果 | 正常终态 | ✅ P1 已落地 |
| **error-end** | 错误终态 ★ | 实例 FAILED + errorMessage（onError goto 的落点） | ★ 新增（P2 随内核错误分支） |
| **terminate-end** | 终止终态 ★ | 终止全部在途分支（其余分支标 cancelled）后实例 TERMINATED | ★ 新增（P2 随内核） |
| **receive-message** | 消息等待 ★ | 等待型：挂起至外部消息/回调（messageKey 驱动推进） | ★ 新增（P3） |
| **respond** | 同步响应 ★ | webhook 触发的流程向调用方同步返回中间结果（与 webhook-trigger 配对） | ★ 新增（P3） |
| **event-wait** | 事件等待 ★ | 等待型：等待订阅事件之一（Event Gateway 对位） | ★ 新增（P3） |

**合计 34 种**（5+2+7+4+3+4+9）。

---

## 四、新增节点的内核语义（实施约定）

| 节点 | NodeKind | 内核行为 | 前置条件 |
|---|---|---|---|
| task/approval | WAITING | 激活即挂起（登记 timeout_at）；人工动作/超时扫描驱动；approval 按多实例策略展开审批人行 | P2 任务治理（51 号 §七） |
| error-end | AUTO | 实例 FAILED + errorMessage=config.message | P2 随内核错误分支（onError goto 落点） |
| terminate-end | AUTO | 其余 running/waiting 节点全部标 cancelled → 实例 TERMINATED | P2 内核小改 |
| decision | AUTO | 按 config 规则行（条件表达式→输出值，首行命中）求值输出 | P2+（复用表达式求值器） |
| receive-message | WAITING | 激活即挂起（登记 messageKey）；外部回调 API 携 key 推进 | P3（配套回调端点） |
| respond | AUTO | 将 config/上游输出写入实例 respondPayload；Webhook 触发链路同步返回 | P3（配套 webhook 同步返回机制） |
| event-wait | WAITING | 挂起至订阅事件之一到达（事件桥驱动推进） | P3（事件桥） |
| loop.parallelMode | AUTO | 并行多实例：一次扇出全部 item 分支（branch_key=iter），join 语义收口 | P2/P3 |

---

## 五、实施映射与分组路由

| 缺口 | 落点 | 前置 |
|---|---|---|
| G1 人工任务族 | P2（后端）+ P5（设计器目录随 P2 先行补 task/approval 两组） | 51 号 §七设计已就绪 |
| G2 端点事件 | P2 内核（error-end/terminate-end 为小改动） | onError 机制已有 |
| G3 parallel/join 前端 | P2 设计器目录先行补（后端已就绪） | 无 |
| G4 消息/事件形态 | P3（receive-message/respond/event-wait 与事件桥同步交付） | P3 事件体系 |
| G5 decision | P2+（表达式求值器复用） | 无 |
| G6 并行多实例 | P2/P3（loop config 扩展） | 无 |

> 前端节点库分两步刷新：**P2 时先补 task/approval/parallel/join 四组**（后端已就绪或随 P2 交付），**P3 再补触发器与事件族**；P5 设计器重做时全量 34 种定稿上库。当前截图中的 23 类旧目录在 P2 前维持现状。

---

## 六、对既有文档的影响

| 文档 | 影响 |
|---|---|
| 51 号 §二 | 26 类目录被本文件 34 类取代（冲突处以本文件为准） |
| 52 号 §四 | VLF_WORKFLOW_NODE.node_type VARCHAR(32) 容量足够；无 DDL 变更 |
| PRD F-026 | 8 类节点全部被 34 类覆盖（gateway→parallel+join、automation→注册表自动化节点） |
| workflowTypes.ts（前端旧目录） | P2 起分批替换为 v2 目录（先补 task/approval/parallel/join） |
