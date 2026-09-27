# 独立专业工作流引擎 · 设计实现方案

> 编号：51-workflow-engine-design-implementation
> 版本：V1.0 · 2026-09-27
> 性质：设计实现方案（依据 50 号调研分析；供立项评审后按分期实施）
> 关联：50（调研分析，取代 48 号 A 域定位裁定与 28-V1.2 边界二分）、30/31（既有设计，分化点已在 50 号 §2.3 勘误）、PRD F-026/F-032~F-036
> 代码基线：分支 `fix/sandbox-security-storage`（编排 O1~O3 已交付）；Flyway 自 **V55** 起编号
> 管控约定：AETC 全程留痕；R2 操作 GATE + 检查点；CR P0/P1 质量门；界面过 U-CHK

---

## 目录

1. [目标架构](#一目标架构)
2. [节点类型体系（26 类）](#二节点类型体系26-类)
3. [数据模型设计](#三数据模型设计)
4. [执行内核设计（持久化等待驱动）](#四执行内核设计持久化等待驱动)
5. [变量与表达式系统](#五变量与表达式系统)
6. [触发体系](#六触发体系)
7. [人工任务治理](#七人工任务治理)
8. [容错与可靠性](#八容错与可靠性)
9. [与编排引擎互操作](#九与编排引擎互操作)
10. [校验规则（WV 体系）](#十校验规则wv-体系)
11. [API 设计](#十一api-设计)
12. [前端设计](#十二前端设计)
13. [兼容与迁移](#十三兼容与迁移)
14. [分期实施计划](#十四分期实施计划)
15. [风险与开放问题](#十五风险与开放问题)

---

## 一、目标架构

```
┌────────────────────────────────────────────────────────────────────────────┐
│ 交互层   工作流设计器(React Flow)   实例/节点时间线   我的待办   审批中心(复用)  │
│          监控仪表盘(30号落地)        通知中心(复用)                             │
├────────────────────────────────────────────────────────────────────────────┤
│ 触发层   手动 │ 定时(Cron) │ Webhook(token) │ 应用事件 │ 上游工作流             │
├────────────────────────────────────────────────────────────────────────────┤
│ 调度层   WorkflowScheduler（持久化等待驱动）                                   │
│          事件驱动推进：节点完成事件 → 求值出边 → 激活后继 → 分派执行              │
│          并行汇聚(join 三策略) │ 循环(item/done) │ 暂停/恢复(节点级协作)         │
├────────────────────────────────────────────────────────────────────────────┤
│ 节点执行层  WorkflowNodeHandler 注册表（26 类节点 → 可插拔 Handler）            │
│   自动型：同步/异步执行完即推进（HTTP/LLM/Agent/编排/转换/延时…）                │
│   等待型：激活即挂起，人工动作/定时器/外部回调驱动（task/approval/delay…）        │
├────────────────────────────────────────────────────────────────────────────┤
│ 定义层   WorkflowService（CRUD+WV 校验双闸+发布闸+版本快照+导入导出）            │
├────────────────────────────────────────────────────────────────────────────┤
│ 治理层   审计切面(AOP) │ 通知(SSE+落库) │ RBAC(发起/办理/审批/管理) │ 监控指标   │
├────────────────────────────────────────────────────────────────────────────┤
│ 共享底座（与编排轨共用）：RBAC │ 通知中心 │ 审批中心 UI │ LLM 网关 │ Agent 运行时 │
│           Wenshi 检索 │ 可观测基础设施                                        │
└────────────────────────────────────────────────────────────────────────────┘
```

**三条架构原则**：

1. **持久化等待驱动（wait-state driven）**：执行状态全在 DB（instance/node_instance 行）；"推进"由三类事件驱动——人工动作（complete/approve）、自动节点完成回调、定时器到期。进程重启无损，人工任务可挂起数天。
2. **节点注册表（handler registry）**：每种节点类型一个 `WorkflowNodeHandler` 实现（接口：`activate()` 激活语义 + `onComplete()` 完成语义），启动时注册进注册表——新增节点类型 = 新增一个 Handler 类，不改内核。
3. **编排机制模式平移**：版本快照、结构校验双闸、重试/退避、CAS 定时调度、Webhook 哈希凭证——直接复用编排轨已验证的模式与代码形态（各自实现，不引入共享中间层），降低设计与评审成本。

---

## 二、节点类型体系（26 类）

统一三套既有模型（50 号 §五裁决）。`node_type` 仍为 VARCHAR(32)，注册表键；端口约定沿用前端愿景（in→out 单进单出，分支类多出）。

### 2.1 触发器（5，触发器节点仅作画布语义锚点，实际触发由触发层执行）

| type | 名称 | 关键 config | 说明 |
|---|---|---|---|
| `manual-trigger` | 手动触发 | formFields?（发起表单字段定义） | startInstance 入口 |
| `schedule-trigger` | 定时触发 | cron、timezone、inputTemplate | 触发层定时配置（V56），画布锚点 |
| `webhook-trigger` | Webhook | path 说明、authHeader? | 触发层 Webhook 配置（V56） |
| `event-trigger` | 事件触发 | eventType（订阅的应用事件键） | 事件总线订阅（§六） |
| `upstream-trigger` | 上游触发 | workflowId? | 被其他工作流 sub-workflow 调用时的入口 |

### 2.2 人工任务（3，差异化核心）

| type | 名称 | 关键 config | 说明 |
|---|---|---|---|
| `task` | 办理任务 | assigneeId/assigneeRole、formFields?、timeoutHours、timeoutAction | 办理人提交表单/输出后推进 |
| `approval` | 审批 | approverIds[]/approverRoles[]、**approvalMode（ANY 或签/ALL 会签/RATIO 按比例）**、timeoutHours、**timeoutAction（escalate 升级/reject 自动驳回/approve 自动通过）**、**rejectTargetNodeId（驳回回退目标，缺省=前一节点）** | 审批通过→继续；驳回→回退原语（§七） |
| `notification` | 通知 | channel（inbox 站内/email/im）、recipients、template | 支持变量模板；发出后自动推进 |

### 2.3 逻辑控制（5）

| type | 名称 | 关键 config | 端口 |
|---|---|---|---|
| `condition` | 条件判断 | expression（§五表达式） | true / false |
| `switch` | 多路分支 | expression + cases[]（值→出边标签）+ default | 各 case / default |
| `loop` | 循环遍历 | arrayExpr（数组取值表达式）、maxIterations（默认 100） | item（逐项）/ done（完结） |
| `parallel` | 并行网关 | **joinStrategy（ALL/FIRST/N_OF_M）、joinCount** | 多出边并行激活 |
| `join` | 汇聚网关 | 与上游 parallel 配对（策略在 parallel 上配置） | 单出 |

### 2.4 数据处理（3）

| type | 名称 | 关键 config |
|---|---|---|
| `transform` | 数据转换 | mapping（目标字段←源表达式映射 JSON） |
| `json-parse` | JSON 解析 | field、targetVar |
| `set-variable` | 变量赋值 | variables[]（key←表达式） |

### 2.5 AI 能力（4，桥接编排轨——AI 深度归编排，工作流只做调用与消费）

| type | 名称 | 关键 config | 后端实现 |
|---|---|---|---|
| `llm` | 大模型调用 | modelProvider/modelName、promptTemplate（变量模板）、temperature、maxTokens | LlmClientRegistry 直调（编排同款客户端） |
| `agent` | 智能体 | agentId、taskTemplate | 复用 AgentExecutor（编排 ReAct 运行时） |
| `orchestration` | 编排图 | graphId（须 active）、inputTemplate | 复用编排 `executeGraphForAgentTool` 入口（同步+时长帽） |
| `knowledge` | 知识检索 | knowledgeBaseId、queryTemplate、topK | Wenshi 检索适配 |

### 2.6 集成对接（4）

| type | 名称 | 关键 config | 安全约束 |
|---|---|---|---|
| `http-request` | HTTP 请求 | method/url/headers/bodyTemplate、timeoutSeconds、retryCount/retryBackoffMs | 出站 SSRF 校验（复用编排 SsrfValidator）；URL 白名单策略默认拒绝内网 |
| `database` | 数据库操作 | operation（select 只读先行）/table/whitelistedQuery | **仅受控数据源**（平台配置的只读报表源）；参数化查询禁拼接；P2 再放开写 |
| `email` | 发送邮件 | to/subject/bodyTemplate | SMTP 配置化；收件人变量模板 |
| `im-notify` | IM 推送 | channel（wecom/dingtalk/feishu）、webhookRef、messageTemplate | webhook 地址入平台配置不落流程定义 |

### 2.7 流程控制（4）

| type | 名称 | 关键 config |
|---|---|---|
| `delay` | 延时等待 | duration、unit（等待型节点：定时器到期驱动推进） |
| `sub-workflow` | 子工作流 | workflowId（须 active，调子流程实例，等待其完成） |
| `counter` | 计数器（P2 可选） | resetOn |
| `return` | 返回结果 | outputExpression（写实例 finalOutput 并结束） |

> **Handler 分两型**：`等待型`（task/approval/delay/sub-workflow——激活即挂起，外部事件驱动）与`自动型`（其余——激活即执行，完成回调推进）。regenerate 26 类中 P1 先交付 12 类（内核验证集，见 §十四），其余分期。

---

## 三、数据模型设计

保留九表骨架，扩展 + 新增三表（Flyway V55/V56/V57）。

### 3.1 V55：核心扩展

```sql
-- workflow_node：node_type 扩为注册表键（VARCHAR(32) 不动，语义扩展）；
-- config JSON 沿用；新增节点版本无关。无需改列。

-- workflow_instance：触发与终态扩展
ALTER TABLE workflow_instance
    ADD COLUMN trigger_type VARCHAR(32) DEFAULT 'MANUAL' COMMENT 'MANUAL/SCHEDULE/WEBHOOK/EVENT/UPSTREAM',
    ADD COLUMN final_output LONGTEXT COMMENT 'return 节点产出/终态输出',
    ADD COLUMN error_message TEXT,
    ADD COLUMN version_id VARCHAR(26) COMMENT '绑定的定义版本快照 ID';

-- workflow_node_instance：并行/循环/重试语义扩展
ALTER TABLE workflow_node_instance
    ADD COLUMN branch_key VARCHAR(64) DEFAULT '' COMMENT '分支键（并行分支序号/loop 迭代号；uk 组成部分）',
    ADD COLUMN iteration INT DEFAULT 0 COMMENT '循环迭代序号',
    ADD COLUMN retry_count INT DEFAULT 0,
    ADD COLUMN error_message TEXT,
    ADD COLUMN timeout_at BIGINT COMMENT '超时到期时间（timeoutAction 驱动）',
    ADD UNIQUE KEY uk_wf_node_inst (instance_id, node_id, branch_key, iteration);
-- 状态枚举扩展（应用层约定，不改列类型）：pending/running/waiting(等待型挂起)/completed/failed/skipped/cancelled

-- workflow_audit_log：PRD F-036 完整字段已有；补 AOP 接入与保留策略（应用层）
```

### 3.2 V56：触发配置

```sql
-- 定时触发（模式平移编排 orchestration_schedule：CAS 抢占即推进 + 自愈）
CREATE TABLE workflow_schedule (
    id VARCHAR(26) PRIMARY KEY,
    workflow_id VARCHAR(26) NOT NULL,
    cron_expr VARCHAR(64) NOT NULL,
    timezone VARCHAR(64) DEFAULT 'Asia/Shanghai',
    input_template VARCHAR(2048),
    enabled TINYINT DEFAULT 1,
    last_fire_at BIGINT, next_fire_at BIGINT,
    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_wf_schedule_graph (workflow_id),
    KEY idx_wf_schedule_fire (enabled, next_fire_at)
);
-- Webhook 触发（模式平移编排 orchestration_webhook：SM3 哈希/404 语义/总开关默认关）
CREATE TABLE workflow_webhook (
    id VARCHAR(26) PRIMARY KEY,
    workflow_id VARCHAR(26) NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    enabled TINYINT DEFAULT 1,
    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_wf_webhook_token (token_hash),
    UNIQUE KEY uk_wf_webhook (workflow_id)
);
```

### 3.3 V57：定义版本快照

```sql
-- 模式平移编排 orchestration_graph_version（WFO-01 已验证）
CREATE TABLE workflow_version (
    id VARCHAR(26) PRIMARY KEY,
    workflow_id VARCHAR(26) NOT NULL,
    version INT NOT NULL,
    definition_snapshot LONGTEXT NOT NULL COMMENT 'nodes+transitions+config 序列化快照',
    published_by VARCHAR(26), published_at BIGINT,
    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_wf_version (workflow_id, version)
);
```

### 3.4 决策说明

- **保留结构化九表而非改用 JSON 原文**：人工任务治理（分派/待办查询/审计）强依赖行级查询，这是工作流与编排存储选型的根本分野；
- **definitions 的 nodes/transitions 仍分表存储**（画布读写用），版本快照表做不可变序列化（发布时聚合 nodes+transitions+config 成 JSON）；
- workflow_permission 两表：`workflow_permission` 开始消费（START/EXECUTE/REVIEW/MANAGE 四权限，发起与办理校验用）；`workflow_permission_matrix` 语义歧义（研发阶段矩阵）**废弃**（V55 不迁移，文档标注），节点级权限由节点 config 的 assignee 模型承担。

---

## 四、执行内核设计（持久化等待驱动）

### 4.1 调度器与推进模型

```
激活节点（INSERT node_instance, status=running/waiting）
   ├─ 等待型 Handler：挂起，登记 timeout_at（approval/task/delay/sub-workflow）
   └─ 自动型 Handler：提交执行（同步快速 / 异步线程池）
        └─ 完成 → 写 output → completeNodeInstance(nodeInstanceId, output)
                → WorkflowScheduler.onNodeCompleted(instanceId, nodeId, branchKey)
                    → 求值该节点出边（表达式）→ 激活后继（含 join 判定/loop 推进）
                    → 全部活动分支归零且到达 end/return → 实例 completed
```

关键机制（对照编排已验证模式）：

1. **推进事件源**：①人工动作 API（completeNode/approve）②自动节点完成回调（同步调用或异步池回调）③定时器到期扫描（delay/approval 超时：@Scheduled 每分钟扫 `timeout_at <= now AND status='waiting'`，CAS 抢占同编排 F-02 修复后的模式——**抢占即推进**，杜绝 NULL 锁停摆）④Webhook/事件触发新实例。
2. **并行与汇聚**：parallel 节点激活时按出边数生成 branch_key（0..n-1）的各分支 node_instance；join 节点按 `joinStrategy` 判定——ALL（等全部分支到达）/FIRST（首个到达即放行，其余分支标 skipped）/N_OF_M（joinCount）。判定逻辑参照编排 mergeExpect/mergeArrived 的计数模式，但落库（join 期待数写 instance.variables 记账，到达计数写 join 节点的 node_instance 输入聚合）。
3. **循环**：loop 节点首次激活求值 arrayExpr → 生成 iteration=0 的 item 分支 → item 分支完成回调 loop → 生成 iteration+1（< maxIterations 且数组未尽）→ 数组尽激活 done 分支。每迭代独立 node_instance 行（branch_key=loop，iteration 递增），全程可审计。
4. **条件/多路**：condition 求值表达式走 true/false 出边；switch 按 cases 匹配，default 兜底；被跳过分支的 join 期待数修正（同编排 ROUTER 跳过分支修正逻辑）。
5. **驳回回退原语（F-033/BR-023 升级）**：approval 驳回 → 按节点 `rejectTargetNodeId`（缺省拓扑前一节点）将目标节点重新激活（新 node_instance 行，branch/iteration 沿用），回退路径上的活动节点全部标 cancelled，variables 快照保留（审计可查）——**原语级回退**，优于 48 号的条件边简化方案。
6. **暂停/恢复（节点级协作）**：暂停 = 实例标 suspended + 停止激活新节点（运行中自动节点跑完即停，等待型节点保持 waiting）；恢复 = 重启调度器推进。与编排的"信号在节点边界生效"语义一致。
7. **幂等与并发**：节点完成事件处理加实例级分布式锁（DragonflyDB SETNX，单实例场景退化为 DB 行锁 UPDATE ... WHERE status='running'）；节点重复完成回调（重试/并发点击）被状态机原子更新吞掉（UPDATE ... WHERE status IN ('running','waiting')，affected=0 即幂等跳过——28 号 §13.4 模式落地）。

### 4.2 自动节点执行池

- 快速节点（transform/json-parse/set-variable/condition/counter）同步执行（调度线程内）；
- 慢节点（http-request/llm/agent/orchestration/knowledge/email/im-notify）提交专用有界线程池（daemon + CallerRunsPolicy，模式同编排 TOOL_TIMEOUT_EXECUTOR，池参数 4/16）；
- 节点超时/重试：模式平移编排 WFO-05（retryCount≤3/backoff/timeoutSeconds，error 事件不重试防副作用）。

### 4.3 事务边界

- 激活节点与推进在同一事务（同实例内多行写）；自动节点执行**不在事务内**（外部调用），完成回写独立事务；
- 长事务规避：join 判定/loop 推进等聚合操作用行级 CAS 更新而非锁全实例。

---

## 五、变量与表达式系统

1. **变量空间**：`trigger`（触发输入）/ `vars`（流程变量，set-variable 可写）/ 节点输出（以 nodeInstanceId 关联，按 nodeId+branch 暴露）/ `loop.item`（循环上下文）。
2. **表达式语言（升级 evaluateCondition）**：新增 `WorkflowExpressionEvaluator`——支持：字面量与变量路径（`vars.order.amount`、`node.n1.output.status`）、比较（9 操作符沿用）、逻辑组合（and/or/not）、括号、函数白名单（`isEmpty/contains/size/now`）——**自研递归下降解析器，禁止脚本语言**（安全红线：无 eval/SpEL/OGNL）；表达式串长度上限 1024。
3. **模板渲染**：`${name}` 沿用编排 VariableTemplates 语义（含 var. 前缀降级），工具/邮件/LLM 提示词等模板统一；结构化渲染（transform/JSON 类节点）parse 后逐字段替换（规避编排 F-10 同款问题——**评审教训前置**）。
4. **注入防护**：表达式求值仅读变量不执行方法（白名单函数表）；模板值回填经 JSON 转义工具。

---

## 六、触发体系

| 触发 | 实现 | 模式来源 |
|---|---|---|
| 手动 | `POST /workflows/instances`（沿用） | 现状 |
| 定时 | workflow_schedule 表 + `WorkflowScheduleRunner`（@Scheduled 每分钟 + CAS 抢占即推进 + repairStuckSchedules 自愈） | 编排 F-02 修复后模式 |
| Webhook | workflow_webhook 表（SM3）+ 匿名端点 `POST /api/v1/workflows/webhooks/{token}`（404 语义 + 总开关 `workflow.engine.webhook.enabled` 默认关 + **body 上限 64KB + 匿名响应仅回 instanceId/status**——49 号 F-04/F-11 教训前置；限流列开启前必办） | 编排 WFC-03 模式 + 评审修复 |
| 事件 | 应用事件注册表（eventType 字符串 → 订阅配置行）；平台发布 Spring 应用事件（如沙箱创建/Agent 执行完成）经 `WorkflowEventBridge` 转发匹配的 enabled 订阅并启动实例；事件载荷作为 trigger 变量 | 新增（单体 Spring Event，RocketMQ 多实例化时切换监听器） |
| 上游 | sub-workflow 节点启动子实例并等待（等待型） | 新增 |

启动即触发校验：实例启动时反查画布 start 触发器类型与实际触发方式一致性（webhook 配置存在才允许 WEBHOOK 触发启动）。

---

## 七、人工任务治理（差异化核心）

1. **分派模型**：`approverIds[] + approverRoles[]` 并集圈定候选人；办理/审批动作校验"当前用户 ∈ 候选人"（越权 403）；未圈定 = 全员可办。
2. **多实例审批**：`approvalMode`——ANY 或签（首个通过即通过，其余自动 skipped）/ ALL 会签（全员通过才通过，一票驳回即驳回）/ RATIO（approveRatio≥阈值）。每位审批人一行 node_instance（branch_key=审批人序号）。
3. **超时治理**：`timeoutHours + timeoutAction`——escalate（升级通知上级/管理员并继续等待）/ reject（自动驳回，走驳回回退）/ approve（自动通过）。超时扫描器 CAS 抢占即推进（同 §四）。
4. **驳回回退原语**：见 §四.5；驳回意见与回退路径全程审计（operation=REJECT + before/after）。
5. **委托与转办**：`POST .../nodes/{nodeInstanceId}/delegate`（delegateTo），候选人变更落 node_instance.assignee_id + 审计。
6. **待办/通知**：`GET /workflows/instances/my-todos`（候选人=我 或 我的角色，按 timeout_at 升序）；通知复用编排轨"落库 + SSE 推送"模式（TASK_CREATED/REVIEW_REQUIRED/TIMEOUT_WARNING/COMPLETED 四类沿用 V1 枚举）；待办 UI 复用审批中心双 Tab 模式扩展"审批流任务"源。

---

## 八、容错与可靠性

| 机制 | 设计 | 模式来源 |
|---|---|---|
| 节点重试 | config retryCount(≤3)/retryBackoffMs；自动节点异常重试，人工驳回不重试 | WFO-05 |
| 节点超时 | 自动节点 timeoutSeconds；等待型节点 timeoutHours+timeoutAction | WFO-05 + Camunda boundary timer |
| 错误分支 | 节点 config `onError: fail|continue|goto:<nodeId>`——fail（默认，实例 failed）/continue（后继收空产出继续，实例如实 failed）/goto（转错误处理节点） | n8n error branch |
| 死信记录 | 自动节点重试耗尽 → node_instance.status=failed + error_message + 实例 failed；监控 API 可查 | taku.ai 评估维度 |
| 幂等触发 | Webhook/事件/定时均带去重键（token+body hash / 事件 ID / next_fire_at CAS） | 编排模式 |
| 审计 | AOP 切面 `@WorkflowAudit` 覆盖 start/complete/approve/reject/cancel/timeout；保留 180 天（定期清理任务） | PRD F-036 |

---

## 九、与编排引擎互操作

| 方向 | 机制 | 状态 |
|---|---|---|
| 工作流 → 编排图 | `orchestration` 节点：复用 `OrchestrationService.executeGraphForAgentTool`（active 校验 + 300s 时长帽 + AGENT_TOOL 语义扩展为 WORKFLOW_CALL 触发类型），编排执行记录 sessionId 填工作流实例 ID（可追溯） | P4 实现；编排侧仅加一个 triggerType 枚举值 |
| 工作流 → Agent | `agent` 节点：直调 AgentExecutor（编排 ReAct 运行时），结果落节点输出 | P4 |
| 编排 → 工作流 | 编排图中新增 `workflow` 节点类型（调用工作流实例并等待） | **后续评估**（需求出现再立项；避免本期双向耦合） |

互操作红线：AI 节点的 Token 消耗计入工作流实例级预算（预算超限触发 timeoutAction=escalate 等人工决策）；编排侧不感知工作流内部结构。

---

## 十、校验规则（WV 体系）

在编排 VL 体系基础上按工作流语义扩展（保存 + 发布双闸，前端实时同源校验）：

| 编号 | 级别 | 规则 |
|---|---|---|
| WV-01 | ERROR | 有且仅有 1 个触发器节点（manual/schedule/webhook/event/upstream 之一） |
| WV-02 | ERROR | 存在 end 或 return 节点 |
| WV-03 | ERROR | nodeId 唯一；边 from/to 引用存在 |
| WV-04 | ERROR | 图无环（condition/switch/loop 的回边除外——按节点类型放行合法回边） |
| WV-05 | ERROR | condition/switch 出边必须可路由（condition 表达式非空 / cases 覆盖 default）；parallel 出边 ≥2 且下游有配对 join |
| WV-06 | ERROR | 节点 config 必填项：approval 的审批人圈定、http-request 的 url、sub-workflow 的 workflowId 等（按节点 Handler 声明的 required 字段校验——注册表自带元数据） |
| WV-07 | ERROR | 表达式语法合法（解析器语法闸）与变量引用可解析（触发输入/流程变量/前驱输出，WARNING 级放行） |
| WV-08 | WARNING | approval 未配置 timeoutAction；loop 无 maxIterations；join 无上游 parallel |
| WV-09 | ERROR | sub-workflow/orchestration 引用目标存在且 active（跨定义校验，模式同 VL-13 BFS 防环） |
| WV-10 | WARNING | webhook/schedule 触发器存在但触发配置未启用（运行期该触发方式不可用） |

---

## 十一、API 设计

前缀 `/api/v1/workflows`（沿用）；30 号分化点以本方案为准（publish/archive/terminate 保留现状语义）。

| 分类 | 端点 | 说明 |
|---|---|---|
| 定义 | 沿用 CRUD/publish/archive/graph | 发布前 WV 校验闸（新增行为） |
| 定义版本 | `GET /{id}/versions`、`POST /{id}/versions/{versionId}/rollback` | 模式平移编排 |
| 实例 | 沿用 start/详情/列表/terminate；`POST /instances` 支持 trigger 上下文 | — |
| 节点动作 | `PUT /instances/{id}/nodes/{nodeInstanceId}/complete`（含 approval 的 approved/comment，沿用）；**新增** `POST .../nodes/{nodeInstanceId}/delegate`（委托） | — |
| 待办 | `GET /workflows/my-todos`（候选人视角） | 新增（修 B5 断点） |
| 通知 | 沿用列表/单条已读；**新增** `POST /notifications/read-all`、`GET /notifications/unread-count`（30 号补齐） | — |
| 监控 | `GET /workflows/monitor/dashboard`（实例计数/状态分布）、`GET /monitor/timeout-nodes`、`GET /monitor/statistics?range=`（30 号监控落地） | 新增 |
| 触发配置 | `PUT/GET /{id}/schedule`、`PUT/GET /{id}/webhook`、`POST /webhooks/{token}`（匿名，总开关+64KB+脱敏响应——49 号教训前置） | 模式平移编排 |
| 流转记录 | `GET /instances/{id}/transitions`（节点完成事件流，30 号补齐） | 新增 |
| 导入导出 | `GET /{id}/export`、`POST /import`（定义 JSON，含 WV 校验） | 新增 |

---

## 十二、前端设计

1. **设计器重做**：`WorkflowDesigner` 基于 @xyflow/react（**复用编排 designer 组件族模式**：Palette/NodeCard/PropertyPanel/校验面板/JSON 双视图/转换层 `workflowTransformer`），废弃 SVG 自绘 `WorkflowCanvas`（46 号已评估天花板低）；26 类节点目录（分 7 组折叠）；触发配置区（定时/Webhook，模式复用编排 TriggerSection）；未配置角标；**保存接线真实 API**（修 B1 假保存）；四主题 token（NFR 延续）。
2. **待办与实例**：我的待办（办理抽屉：表单/审批动作/意见必填/委托）；实例详情节点时间线（node_instance 行级展示：状态/处理人/耗时/输出/重试）；监控仪表盘（30 号 §3.4 落地：统计卡/状态饼图/超时列表）。
3. **文案边界**：工作流页 =「工作流」；编排页 =「编排引擎」（46 号 R3 延续）。
4. 状态字面量统一（后端小写枚举 code + 前端映射函数——修 B4）。

---

## 十三、兼容与迁移

| 项 | 策略 |
|---|---|
| 存量工作流（3 张测试图） | 定义不变；node_type 旧 6 类映射到新体系（start→manual-trigger、end→return、task/approval 原样、condition 原样、subprocess→sub-workflow）；打开旧图为空图兜底+引导 |
| workflow_permission_matrix | 废弃（不迁移，文档标注）；workflow_permission 开始消费 |
| 状态字面量 | 后端统一小写；存量实例行 UPDATE 映射（V55 附带数据订正） |
| 编排引擎 | 不受影响；orchestration triggerType 增 WORKFLOW_CALL 枚举值（V55 顺带） |
| admin-web 死常量 | 顺手清理 |

---

## 十四、分期实施计划

总估算 **31~41 人日**；每期出口绑定验收冒烟（Doc-Gate），AETC 分期留痕。

### P1 内核重构与基础节点集（8~10 人日，P0）

| 任务 | 内容 | 估算 |
|---|---|---|
| T1.1 | 节点注册表框架（WorkflowNodeHandler 接口 + 注册表 + 元数据 required 字段声明） | 1.5 |
| T1.2 | WorkflowScheduler：激活/推进/join 三策略/loop 推进/幂等状态机/事务边界 | 3 |
| T1.3 | V55 迁移 + 实体扩展 + 状态字面量统一 + 存量映射 | 1 |
| T1.4 | 表达式求值器 + 变量空间 + 结构化渲染 | 2 |
| T1.5 | 基础节点集 12 类：manual-trigger/condition/switch/loop/parallel/join/transform/json-parse/set-variable/http-request/delay/return | 2.5 |
| T1.6 | WV 校验器（V1~V7）+ 发布闸 + 保存接线 | 1.5 |
| T1.7 | 测试：调度器/join/loop/幂等/表达式单测 + 冒烟 | 1.5 |

**P1 验收**：含并行汇聚/循环/HTTP 节点的流程端到端跑通（设计→保存→发布→发起→自动推进→return）；重复完成回调幂等；进程重启后 waiting 节点可续。

### P2 人工任务治理（6~8 人日，P0）

| 任务 | 内容 | 估算 |
|---|---|---|
| T2.1 | task/approval 等待型语义 + 分派模型（候选校验/越权 403） | 2 |
| T2.2 | 会签 ANY/ALL/RATIO 多实例 + 审批人行 | 2 |
| T2.3 | 超时治理（timeoutHours+escalate/reject/approve + 扫描器） | 1.5 |
| T2.4 | 驳回回退原语 + 委托转办 + 审计 AOP | 1.5 |
| T2.5 | my-todos API + 通知 SSE + 待办 UI（办理抽屉） | 2 |
| T2.6 | 测试与冒烟（会签全链/驳回回退/超时自动处理） | 1 |

### P3 触发体系（5~7 人日，P1）

T3.1 定时（V56+Runner 平移）；T3.2 Webhook（V56+端点+安全语义）；T3.3 事件桥（事件注册表+Spring Event Bridge）；T3.4 上游触发与 sub-workflow 节点；T3.5 测试冒烟。——共 5~7。

### P4 AI 节点与版本化（4~6 人日，P1）

T4.1 llm/agent 节点（复用 LlmClientRegistry/AgentExecutor + 预算计入）；T4.2 orchestration 节点（编排侧加 WORKFLOW_CALL 枚举）；T4.3 knowledge 节点（Wenshi 适配）；T4.4 版本快照（V57+发布聚合+执行绑定版本+回滚）——共 4~6。

### P5 设计器与可观测（8~10 人日，P1）

T5.1 React Flow 设计器（26 类目录/画布/属性面板/校验面板/JSON 双视图/触发配置区）；T5.2 实例时间线 + 重放着色；T5.3 监控 API 与仪表盘（30 号落地）；T5.4 导入导出 + 模板库（3~5 个预置：审批发布链/定时巡检/HTTP 数据同步/带 AI 的处理链/多级会签）；T5.5 手册与 UAT——共 8~10。

### 依赖关系

P1 → P2 →（P3 ∥ P4）→ P5；P3/P4 内部任务可并行。设计器（P5.1）可在 P2 后提前启动骨架（依赖 P1 的节点注册表元数据生成节点目录）。

---

## 十五、风险与开放问题

### 风险

| # | 风险 | 缓解 |
|---|---|---|
| R1 | 执行内核重构破坏既有 start→complete 链路 | P1 先交付 12 类验证集并行回归；状态机原子更新模式保幂等 |
| R2 | 并行/loop 的分支记账复杂度（branch_key/iteration 唯一键） | 模式先在单测内验证；join 计数落 instance.variables 可审计 |
| R3 | 会签/回退原语的状态组合爆炸 | P2 单独分期 + 全组合单测矩阵 |
| R4 | 双引擎概念混淆（26 类 vs 编排 8 类） | 文案边界 + 节点目录命名区分；手册各自独立 |
| R5 | AI 节点预算失控 | 实例级预算 + escalate 升级人工（ADR-004 精神平移） |
| R6 | 事件触发的事件源质量（平台事件键不稳定） | 事件注册表显式订阅 + 版本化事件键 |

### 开放问题（需拍板）

1. `database` 节点是否 P1 即放开写操作（建议 P1 仅 select 只读、P2 再评估白名单写）；
2. 审批升级（escalate）的上级关系来源（组织架构模块缺失，建议先"通知管理员"简化）；
3. 编排→工作流反向触发（编排图调工作流）是否立项（建议维持单向，需求出现再评估）；
4. 与 AI 交互整套流程的关系（docs/AI交互架构分析）是否需要工作流承载对话式流程——本期不覆盖。

---

*本方案依据 50 号调研分析编写；实施启动以 P1 分期立项为准。*
