# ATLAS-RULES.md — 智能体执行留痕与人机协同平台对接作业细则（ATLAS V1.0）

> 本文件是《智能体执行留痕与人机协同平台规范》（ATLAS V1.0）的 Agent 侧作业细则，
> 入口文件 `.standards/ATLAS.md` 触达后按本细则执行。规范 PDF 定本见
> `01_规范文档/智能体执行留痕与人机协同平台规范ATLAS-V1.0.pdf`。
> 数据契约（JSON Schema）见本目录 `schemas/`；对接配置模板见 `config.yaml`。

## 一、接入条件与 Agent 侧组件

| 组件 | 职责 |
|---|---|
| 任务执行核心 | Agent 本体：规划、工具调用、代码编辑——行为对接前后不变 |
| Trail Adapter | 订阅本地留痕（AETC JSONL）→ 按映射规则转 TraceEvent → 脱敏过滤 → 哈希签名 → 本地 WAL 缓存 → 批量推送 |
| 回路客户端 | 决策工单上报、长轮询领取决议（挂起 25 秒）、指令领取与执行、回执上送 |

连接原则：所有网络连接一律由 Agent 侧发起**出站**请求（HTTPS）；平台永不主动连接 Agent。
Agent 可部署在任意内网/容器/本机，无需开放入站端口。

## 二、事件上行通道

### 2.1 统一事件模型 TraceEvent

| 字段 | 类型 | 约束 |
|---|---|---|
| event_id | UUID | 全局唯一幂等键，平台按此去重，重复推送安全 |
| event_type | 枚举 | 见 §2.2 事件类型表；自定义类型须以 `x-` 前缀命名空间隔离 |
| severity | 枚举 | info / notice / warning / critical；critical 触发平台即时告警 |
| timestamp | ISO 8601 | 必须带时区偏移；单 session 内最大偏移 60 秒 |
| agent | 对象 | agent_id（注册分配）/ agent_type / version / session_id |
| context | 对象 | trace_id / task_id / project / phase 等归组字段 |
| payload | 对象 | 事件类型相关数据，按 event_type 的 schema 校验 |
| artifact_refs | 数组 | 大对象（diff/日志/截图）不进 payload，以工件引用挂接 |
| prev_event_hash / event_hash | 字符串 | 哈希链字段，见 §2.4 |

### 2.2 事件分级与类型枚举

- **L1 任务级**：session.started/ended、task.started/completed/failed/cancelled、
  task.suspended/resumed——时间线骨架，**全量推送**。
- **L2 操作级**：tool.invoked/result、file.read/created/modified/deleted、
  command.executed、change.reported、decision.*、command.*——操作明细，**全量推送**。
- **L3 调试级**：中间推理、上下文快照、探索性读——**采样推送**（采样率可配置），
  平台可下发补采指令，Agent 从本地 WAL 补发完整 L3。

L1+L2 构成经验学习所需最小轨迹集；L3 服务深挖排查。

### 2.3 推送协议

- 批量推送：批内事件按 timestamp 排序，逐条携带请求头
  X-Agent-Id / X-Timestamp / X-Nonce / X-Signature（HMAC-SHA256）。
- 失败重试：指数退避 1s/4s/16s/64s（上限 5 分钟），期间事件在本地 WAL 排队。
- WAL 保护：超过 256MB 或 24 小时触发强制转储为工件并上报 `wal.overflow`，保证不丢账。
- 断网恢复：WAL 断点续传（进程重启/断电后从 WAL 恢复）。

### 2.4 哈希链与防篡改

- event_hash = SHA256(前条 event_hash + 本条事件除哈希字段外的规范序列化 JSON)，
  按 session 成链，链头锚定 session.started。
- 平台入库逐条校验：断链/跳号/哈希不匹配 → `audit.chain.broken` 告警，
  冻结该 session 证据效力标记。
- 每日终了，各 session 链尾哈希汇总为日锚点写入追加归档（WORM）。

## 三、变更呈报（ChangeReport）

D1 及以上代码变更随提交呈报 ChangeReport，必含字段：report_id、task_id、trace_id、
intent（一句话意图与动机）、risk_level、changes（path/op/±行数）、diff_ref（工件引用）、
impact（调用方分析/受影响测试/契约影响）、evidence（测试与构建证据）、rollback（回滚方式）、commit。

呈报质量红线：intent 只写"修改了 X"这类语义重复描述、impact 缺调用方分析、
evidence 缺失（无测试结果且非纯文档变更）——三者均会被平台标黄；
连续三次标黄的 Agent 实例触发呈报质量整改通知。

## 四、人工决策回路

### 4.1 决策点分级（D0–D3，默认矩阵可收紧不可放宽）

| 级别 | 触发条件示例 | 呈报与阻塞 | 审批人 |
|---|---|---|---|
| D0 免审 | 只读、沙箱试探、方案草稿 | 仅 L3 留痕 | 无 |
| D1 事后审计 | 常规代码修改、单文件新增、文档变更 | 呈报不阻塞 | 事后查阅 |
| D2 事前确认 | 跨文件重构、删文件、依赖变更、schema 迁移、推送远端 | 呈报+工单，Agent 挂起 | 单人 |
| D3 强审批 | 生产操作、对外发送、数据删除、付费调用、安全配置变更 | 呈报+工单，Agent 挂起 | 双人四眼 |

健康度红线：平台按周统计拦截率（触发工单的任务占比），<5% 分级过松、>15% 分级过严，
均要求管理员重新校准矩阵。

### 4.2 决策工单 DecisionTicket 状态机

PENDING →（APPROVED / MODIFIED / REJECTED / EXPIRED / CANCELLED）→ RESUMED → CLOSED。

- 工单必含：decision_id、级别、一句话问题陈述、上下文引用（ChangeReport/关键事件）、
  选项集（approve/modify/reject 及后果描述）、TTL 与升级策略、审批人路由。
- D3 工单两名审批人先后确认，第二人可见第一人意见。
- **EXPIRED = fail-safe 默认拒绝**：Agent 执行回滚并落 task.failed。
- RESUMED 后必须回传执行结果（含 diff 与验证证据）才能 CLOSED。
- 审批记录留存三年，任何人不可删除修改，只能追加。

### 4.3 Agent 等待行为

遇到 D2/D3 决策点：先落 task.suspended 事件 → 上报工单 → 挂起长轮询（25 秒/次）→
领取决议（decision.result）→ 落 task.resumed → 执行决议 → 回传执行结果 → 工单 CLOSED。
MODIFIED 决议按修改意见执行；REJECTED 按终止分支执行并回滚现场。

## 五、指令下行通道

### 5.1 指令类型白名单（五类）

| 类型 | 语义 | 权限 |
|---|---|---|
| task.dispatch | 派发新任务 | 审批者 |
| task.cancel | 取消运行中任务（Agent 保存现场后中断并回执） | 管理员 |
| task.rerun | 按历史规格重跑（新 trace_id 关联原任务） | 审批者 |
| policy.update | 热更新决策分级/护栏策略，回执生效版本 | 管理员 |
| decision.clarify | 对 PENDING 工单补充说明（不改变状态） | 审批者 |

### 5.2 四要素本地校验（不可绕过、不可配置关闭）

1. 类型在白名单内；2. scope 与当前授权项目匹配；3. 未过有效期（expires_at，默认 60 分钟）；
4. 平台签名可验证。任一不通过：拒绝执行 + 立即回执拒绝原因 + 平台产生 command.rejected 告警。

### 5.3 回执与边界

- 每条指令必须有回执（command.result）：执行结果、耗时、证据引用。
- task.dispatch 回执必须携带新任务的 trace_id。
- 指令不是审批的捷径：指令派发的任务触发 D2/D3 决策点时照常进决策回路，两者正交。
- 过期未领取的指令自动作废并通知发起人。

## 六、安全与可靠性基线

- **鉴权**：HMAC-SHA256(secret, timestamp + nonce + body)；timestamp 窗口 ±5 分钟防重放。
- **权限**：RBAC（审批者/管理员/观察者）；越权操作平台一律 403 并产生审计事件。
- **脱敏**：密钥、token、内网地址、个人信息在推送与 diff 工件中零泄漏（推送前过滤）。
- **WAL 先落盘后推送**：进程重启、断电后断点续传；平台入库与索引分阶段提交。
- **平台单向可信**：两侧数据不一致以平台账本为准，以哈希链校验裁决。

## 七、接入实施（四阶段）

P1 平台部署与注册（agent_id 分配/密钥协商）→ P2 事件上行接通（A1/A2/A5 验收）→
P3 决策回路接通（A3/A4 验收）→ P4 指令通道接通（A6 验收，全量 A1–A8 复测）。

## 八、验收自检清单（A1–A8，对接必测）

| 项 | 测试 | 通过标准 |
|---|---|---|
| A1 | 幂等重放 | 同批次事件重放 3 次，平台计数不变，无重复告警 |
| A2 | 断网续传 | 断网 24h 恢复，WAL 完整补发，seq 无跳号 |
| A3 | 决策端到端 | 工单创建至 IM 通知 ≤5s；决议发布至 Agent 领取 ≤10s |
| A4 | fail-safe | TTL 耗尽工单正确转 EXPIRED，Agent 回滚并落 task.failed |
| A5 | 哈希链完整性 | 抽样篡改任一事件，平台告警且证据效力标记生效 |
| A6 | 越界指令拒绝 | 伪造类型/跨 scope/过期/坏签名四类指令全部被拒并告警 |
| A7 | 脱敏抽检 | 注入 50 条敏感样本，推送与 diff 工件零泄漏 |
| A8 | RBAC | 越权操作全部 403，且产生审计事件 |

## 九、与统一体系其他规范的对接

- **AETC**：Trail Adapter 订阅 AETC 本地留痕（audit/traces/*.jsonl）按映射规则转推；
  AETC 的 GATE_REQUEST 审批流在对接 ATLAS 后映射为 D 级决策工单（本地留痕义务不变）。
- **AEGIS**：AEGIS 审批门 GATE = 本规范 D2/D3 工单；修复作业全程事件（P0–P6、
  三单产出、安灯升级）按 TraceEvent 上报；教训条目以事件形式回流自进化体系。
- **OPS**：事件处置状态（E1–E7 节点）可选同步平台；E5 审批执行走 D 级工单；
  OPS 的"证据链+回滚预案+人工审批"三确认与 D3 四眼原则对齐。
- **LR/SDLC**：跨文件重构（D2）、依赖变更（D2）等决策点上报工单等待人工。

## 十、目录与配置索引

```
.standards/atlas/
├── ATLAS-RULES.md        ← 本文件（作业细则）
├── config.yaml           ← 对接配置模板（端点/密钥引用/脱敏/采样/TTL/白名单）
├── schemas/
│   ├── trace-event.schema.json       ← TraceEvent 数据契约
│   ├── change-report.schema.json     ← ChangeReport 数据契约
│   ├── decision-ticket.schema.json   ← DecisionTicket 数据契约
│   └── command.schema.json           ← Command 数据契约
└── scripts/
    └── atlas_check.py    ← 本体门禁校验（G1–G5）
```
