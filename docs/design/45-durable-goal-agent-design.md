# 持久化目标驱动长任务（小时级～天级）功能详细设计

| | |
|---|---|
| 文档编号 | 45 |
| 状态 | 详细设计，待审核后实施 |
| 关联 | 43（长任务调研）、44（网页终端）、42（同步/转向等会话增强）、27（沙箱设计）、编排引擎（AutonomousExecutor/GoalPlanner/OrchestrationCheckpointStore） |
| 需求输入 | 长任务支撑到小时级乃至**按天**执行时长；目标性任务**直到达到设置的目标标准为止**；期间支持**人工审核确认** |

---

## 0. 结论与形态判定

**新增"持久化目标作业（Durable Goal Job）"层**：把现有编排层 `AutonomousExecutor` 的"分解→执行→验收→反思重做"循环，改造为**数据库驱动、跨进程、可按天执行**的分段持久化作业。

三条形态判定（本设计的地基）：

1. **执行与连接解耦**：天级任务不能绑定在 SSE 连接或 JVM 进程生命周期上——作业状态全部落库，由**调度器驱动的分段执行**推进；每段是一次受预算约束的 agent 回合，段间落检查点。进程重启后从检查点续跑（消除缺口 R1）。
2. **复用而非重建**：单段执行完整复用 `AutonomousExecutor`（分解/执行/验收/反思）与全部既有机制（转向、文件清单注入、降级、子代理、沙箱卷持久化）；检查点持久化直接实现已有的 `OrchestrationCheckpointStore` SPI。**不引入 Temporal/工作流引擎等新依赖**。
3. **目标判定双层 + 人工审核门**：目标达标 = 机械检查（命令/断言，确定性）优先 + LLM 判官（引用证据防投机）；达标后进入**人工审核门**（可配置强制），通过才算完成——"目标标准"的最终解释权在人工。

## 1. 目标与非目标

**目标**
- 目标性任务按天自主推进：每天/每冷却周期自动执行一个"段"，直至目标标准达成
- 目标标准可配置（验收标准文本 + 机械检查命令 + 是否需人工审核）；达标判定双层化
- 期间人工可：审核通过/驳回、暂停/恢复/取消、插话补充指令、随时查看进度与证据
- 进程重启/崩溃后任务从最近检查点自动续跑
- 成本与范围硬约束：日 token/成本上限、总天数 deadline、最大段数

**非目标**
- 实时流式展示天级任务全程（段间为异步批处理，段内流式仅执行期间可见）
- 替换 veloflow 图编排（静态图编排与目标驱动动态循环是两个形态，不合并）
- 跨机器分布式调度（M1 单实例 CAS 抢占；多实例用 SKIP LOCKED，见 §9）

## 2. 总体架构

```
┌────────────┐  创建/审核/暂停   ┌────────────────────────────┐
│ 前端任务详情页 │ ──────────────→ │ gewu-interface              │
│ (任务详情/审核) │                │ LongTaskController/Service  │
└────────────┘                  │   ├─ 状态机(落库)            │
                                │   ├─ ticket式审核队列        │
                                │   └─ 目标评估器(机械+LLM判官) │
                                └──────────┬─────────────────┘
                                           │ 每段执行（复用）
                    ┌──────────────────────┴──────────────────┐
                    │ LongTaskScheduler @Scheduled             │
                    │  抢占到期作业 → 执行一个"段"：              │
                    │   AutonomousExecutor（分解/执行/验收/反思） │
                    │   + 段预算 + 转向 + 子代理 + 文件工具        │
                    │   → 落检查点 → 目标评估 → 推进/暂停/完结     │
                    └─────────────────────────────────────────┘
```

- **段（Segment）**：一次受段预算约束的 agent 执行回合（时间上限默认 30 分钟、轮次上限 20），是调度/恢复/计费的最小单位
- **段间**：不占用任何运行时资源（沙箱按空闲回收、卷持久、LLM 连接全断），天然适配按天节奏
- **段内**：完整复用现有执行链（转向/子代理/文件工具/降级/心跳）

## 3. 状态机

```
            创建(QUEUED)
                 │ 调度器领取
                 ▼
   ┌───────► RUNNING ────段结束──► GOAL_EVAL
   │           │ │                    │
   │           │ │ ├─人工暂停          ├─达标+需审核──► WAITING_REVIEW
   │   段完成  │ │ ├─日预算超限→PAUSED │                  │通过    │驳回(+反馈)
   │   未达标  │ │ └─用户取消→CANCELLED│                  ▼        │
   │           │ │                     │◄─────────────────────────┘
   │           ▼ ▼                    ├─未达标: 失败< N 段→继续下一段(冷却)
   │          PAUSED◄──────(自动/人工) ├─连续 N 段无进展──► BLOCKED(通知人工)
   │           │恢复                   ├─deadline 到──► EXPIRED(人工收尾)
   └───────────┘                       └─不可恢复异常──► FAILED
   任意非终态 ──人工──► CANCELLED
   COMPLETED / FAILED / EXPIRED / CANCELLED = 终态
```

| 状态 | 语义 | 进入条件 | 退出 |
|---|---|---|---|
| QUEUED | 已创建待首段 | 创建成功 | 调度器领取 |
| RUNNING | 正在执行某段 | 领取/CAS 抢占 | 段结束→GOAL_EVAL；人工暂停/取消 |
| GOAL_EVAL | 段后目标评估中 | 段完成 | 评估落库后按结果迁移（评估同步执行，毫秒~秒级，不单独占状态） |
| WAITING_REVIEW | 等待人工审核 | 达标+review_required，或里程碑审核点 | 通过→COMPLETED；驳回→RUNNING（反馈入下一段）；超时按策略 |
| PAUSED | 人工/日预算暂停 | 日预算超限、人工暂停 | 恢复→RUNNING；取消 |
| BLOCKED | 连续 N 段无进展/反复失败 | 评估判定 | 人工驳回反馈→RUNNING；取消 |
| COMPLETED | 目标达成（含审核通过） | 审核通过或免审达标 | 终态 |
| FAILED/EXPIRED/CANCELLED | 终态 | 系统不可恢复/deadline/人工 | 终态 |

## 4. 数据模型（DDL 概要，V77 迁移）

```sql
-- 目标作业主表
CREATE TABLE agent_long_task (
    id               VARCHAR(26) PRIMARY KEY,           -- ULID
    session_id       VARCHAR(26) NOT NULL,              -- 归属会话（消息流/文件树/终端复用）
    title            VARCHAR(200) NOT NULL,
    goal_text        TEXT NOT NULL,                     -- 目标声明（自然语言）
    criteria         TEXT NOT NULL,                     -- 验收标准（JSON：statement + checks[]）
    review_required  TINYINT NOT NULL DEFAULT 1,        -- 达标后是否必须人工审核
    status           VARCHAR(20) NOT NULL,              -- QUEUED/RUNNING/WAITING_REVIEW/PAUSED/
                                                        -- BLOCKED/COMPLETED/FAILED/EXPIRED/CANCELLED
    segment_count    INT NOT NULL DEFAULT 0,
    max_segments     INT NOT NULL DEFAULT 100,          -- 硬顶（防失控）
    max_days         INT NOT NULL DEFAULT 7,            -- deadline 天数
    deadline_at      BIGINT NOT NULL,                   -- 创建时间 + max_days
    cooldown_minutes INT NOT NULL DEFAULT 5,            -- 段间冷却
    daily_token_cap  BIGINT NOT NULL DEFAULT 0,         -- 0=不限；按自然日累计
    total_tokens     BIGINT NOT NULL DEFAULT 0,
    fail_streak      INT NOT NULL DEFAULT 0,            -- 连续无进展段数（≥N→BLOCKED）
    next_run_at      BIGINT NOT NULL,
    runner_id        VARCHAR(64) DEFAULT NULL,          -- 抢占实例标识
    last_heartbeat   BIGINT DEFAULT NULL,
    progress_summary TEXT DEFAULT NULL,                 -- 最新目标进度摘要（列表页直读）
    created_by       VARCHAR(26) NOT NULL,
    deleted TINYINT DEFAULT 0, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_session_task (session_id),            -- 一会话一活动长任务（简化归责）
    KEY idx_status_next (status, next_run_at)
) COMMENT='持久化目标驱动长任务';

-- 段记录（恢复最小单位 + 审计）
CREATE TABLE agent_long_task_segment (
    id            VARCHAR(26) PRIMARY KEY,
    task_id       VARCHAR(26) NOT NULL,
    seq           INT NOT NULL,                        -- 段序号（1 起）
    wave          INT NOT NULL DEFAULT 1,              -- 预留：段内子代理波次统计
    status        VARCHAR(20) NOT NULL,                -- running/success/failed/blocked/interrupted
    seed_prompt   TEXT NOT NULL,                       -- 本段种子提示（目标+检查点摘要+审核反馈）
    summary       TEXT DEFAULT NULL,                   -- 段结束摘要
    checkpoint    MEDIUMTEXT NOT NULL,                 -- 检查点 JSON（§6）
    goal_eval     TEXT DEFAULT NULL,                   -- 目标评估结果 JSON（§7）
    tokens        BIGINT NOT NULL DEFAULT 0,
    cost_yuan     DECIMAL(12,4) DEFAULT NULL,
    error_msg     VARCHAR(500) DEFAULT NULL,
    started_at    BIGINT NOT NULL, finished_at BIGINT DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_seg (task_id, seq),
    KEY idx_task_seg (task_id, seq)
) COMMENT='长任务分段执行记录';

-- 人工审核记录
CREATE TABLE agent_long_task_review (
    id          VARCHAR(26) PRIMARY KEY,
    task_id     VARCHAR(26) NOT NULL,
    segment_seq INT NOT NULL,
    kind        VARCHAR(20) NOT NULL,                  -- goal_reached / milestone / manual
    verdict     VARCHAR(20) NOT NULL DEFAULT 'pending',-- pending/approved/rejected
    comment     TEXT DEFAULT NULL,                     -- 审核意见（驳回时注入下一段）
    reviewer    VARCHAR(26) DEFAULT NULL,
    created_at  BIGINT NOT NULL, decided_at BIGINT DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_review_task (task_id, verdict)
) COMMENT='长任务人工审核';
```

检查点内容（segment.checkpoint JSON，恢复核心）：
```json
{ "progressSummary": "结构化目标进度", "planSnapshot": "<!--PLAN:...-->",
  "fileInventory": ["path1", "..."], "keyFindings": ["..."], "nextDirective": "下一步建议",
  "budgetConsumed": { "tokens": 0, "costYuan": 0 }, "failStreak": 0 }
```

## 5. 分段执行设计（核心）

**段内复用现有执行链**：段 = 一次 `AutonomousExecutor` 风格的回合执行（分解→执行→验收→反思），底层即 ReactAgentExecutor 执行流——转向、文件清单注入、子代理、降级、心跳全部自动生效。

**段预算**：时间上限 `segment-timeout-seconds`（默认 1800）、轮次上限 20、token 上限=剩余日配额；到限不报错，**落检查点后正常收段**（段是设计好的断点，不是故障）。

**段收尾四件事**（LongTaskSegmentRunner 段完成后同步执行）：
1. 落段记录 + 检查点（进度摘要/计划快照/文件清单/下一步建议——由段的收尾轮 LLM 按固定 JSON schema 生成）
2. 目标评估（§7）
3. 状态迁移（继续/审核/暂停/阻塞）与 `next_run_at = now + cooldown`
4. 进度通知（站内横幅；milestone 通知）

**崩溃恢复**：段 `running` 且 `last_heartbeat`（调度器每 30s 刷新）超过 10 分钟 → 判 interrupted → 任务回 QUEUED（next_run_at=now），从上一检查点续跑；段内半成品文件在卷中保留（幂等由模型按检查点自查）。

**调度互斥**：单实例用 status CAS（`UPDATE ... SET status='RUNNING', runner_id=? WHERE id=? AND status IN ('QUEUED','RUNNING') AND next_run_at<=?`，影响行数=0 即跳过）；多实例部署演进用 `FOR UPDATE SKIP LOCKED`（§9）。

## 6. 目标评估设计（双层 + 防投机）

```json
"criteria": {
  "statement": "完成证券量化交易应用服务，可运行、含回测报告",
  "checks": [
    { "type": "command", "cmd": "mvn -q test", "expect": "exit 0", "weight": "must" },
    { "type": "file_exists", "path": "projects/eleven/report/backtest.md" },
    { "type": "llm_judge", "prompt": "对照验收标准逐条核对当前仓库产物，输出 achieved/gaps/audit" }
  ],
  "review_required": true,
  "milestone_every_segments": 6
}
```

- **机械检查优先**：`command`（沙箱 exec，exit/输出断言）与 `file_exists` 为客观证据；任一 must 检查失败 → 未达标
- **LLM 判官**：以目标声明 + 验收标准 + 机械检查证据 + 仓库产物清单为输入，输出 `{achieved, confidence, gaps[], evidence[]}`；**判官提示词强制"仅可引用机械检查输出与文件内容作为证据"**，防目标投机（模型自我宣称达标）
- **评估频率**：每段结束评估一次（机械检查每次；llm_judge 可配每 K 段一次降成本）
- **评估结果**：`goal_eval` JSON 落段记录 → 前端进度卡按 checklist 逐项渲染 ✓/✗/证据

## 7. 人工审核闭环

**触发点**（满足其一即产生 `agent_long_task_review` 记录，kind 区分）：
1. `goal_reached`：目标判定达标且 `review_required=1`
2. `milestone`：每 `milestone_every_segments` 段强制一次过程审核
3. `manual`：用户随时在详情页发起
4. `blocked`：连续 N 段无进展自动转人工

**审核包**（详情页呈现）：目标 checklist 状态 + 最新评估报告 + 变更文件 diff 列表（复用回合文件汇总）+ 关键产物链接 + 段时间线。

**决策语义**：
- **通过（approved）**：任务 COMPLETED，聚合产物交付至会话（文件卡/消息）
- **驳回（rejected + comment）**：审核意见写入下一段种子提示（"审核驳回：xxx，请整改"），任务回 RUNNING——人工反馈与自主执行形成闭环
- **审核超时**：`review-timeout-days`（默认 3）→ 自动 PAUSED 并通知，不静默推进

**通知**：复用站内横幅 + 会话列表角标（WAITING_REVIEW 状态高亮）；邮件/IM 通道留接口不在本期。

## 8. 按天节奏与资源治理

- **段间冷却**：`cooldown_minutes`（默认 5）——段间释放全部运行时资源（沙箱空闲回收、LLM 连接断开），仅 DB 状态存续
- **执行窗口（可选）**：`work-window`（如 09:00-21:00）外自动 PAUSED，次窗口自动恢复——按天任务适配人工在场的协作节奏
- **日预算**：自然日累计 token/成本超 `daily_token_cap` → PAUSED + 通知，次日 0 点自动恢复；段级上限=剩余日配额
- **硬顶**：max_segments（默认 100）、max_days（默认 7）、deadline_at 到期 → EXPIRED（人工收尾），绝不静默无限跑
- **沙箱**：段间停止不丢卷（文件持续）；段启动自动 ensure——按天任务与沙箱生命周期天然解耦

## 9. API 设计（挂 /api/v1/long-tasks，复用现有鉴权）

```
POST   /                                创建（校验 dev/目标角色、会话归属；支持从既有会话一键升级）
GET    /?status=&projectId=             列表（状态/进度摘要/下一步动作）
GET    /{id}                            详情（状态机 + 段历史 + 评估 + 审核记录）
POST   /{id}/pause | /resume | /cancel  人工控制（pause 段结束后生效）
POST   /{id}/review                     审核 {verdict, comment}
POST   /{id}/steer                      补充指令（写入下一段种子 + 即时可见）
GET    /{id}/segments?page=             段历史分页
```

安全：创建/控制校验会话归属（created_by）；审核仅 task 创建者或 ADMIN；评估命令在沙箱内执行沿用 CommandValidator 白名单。

## 10. 前端设计（要点）

- **入口**：会话页长任务横幅（状态/进度/下一步动作，复用 activeRun 横幅模式）→ 点击进任务详情页
- **任务详情页**：目标卡（checklist 渲染 + 达标进度）+ 段时间线（每段摘要/预算/评估徽标）+ 审核操作区（通过/驳回+意见）+ 终端/文件树入口（复用开发空间组件）
- **通知**：WAITING_REVIEW / BLOCKED / PAUSED 三态高亮 + 会话列表角标
- **交互原则**：长任务详情为"监控+审核"视图，执行细节（消息流/终端）跳转会话页，避免双视图维护

## 11. 分期与工作量

| 阶段 | 内容 | 工作量 |
|---|---|---|
| M1 | 持久化状态机 + 分段执行 + 检查点/恢复 + 调度器（DDL V77 + 3 表 + LongTaskScheduler/Runner） | 4~5 天 |
| M2 | 目标评估双层（机械检查 + LLM 判官）+ 进度卡 | 2~3 天 |
| M3 | 人工审核闭环（审核表/API/通知/驳回反馈注入） | 2~3 天 |
| M4 | 前端任务详情页 + 会话横幅 + 列表 | 3~4 天 |
| **合计** | M1~M3（后端闭环）| **8~11 人日**；M4 后全功能 |

## 12. 验收清单

1. 创建天级目标任务 → 重启 interface → 任务自动从最近检查点续跑（R1 消除验证）
2. 目标达标 → WAITING_REVIEW → 审核通过 COMPLETED；驳回后任务带反馈继续
3. 机械检查失败的段 → 评估"未达标"且 gaps 引用具体命令输出（非模型自称）
4. 连续 3 段无进展 → BLOCKED + 通知；日预算超限 → PAUSED 次日恢复
5. 段间沙箱停止/重启不影响任务推进；卷内文件跨段持续
6. deadline 到期 → EXPIRED（人工收尾），无静默无限执行
7. 全程审计：段记录/评估/审核决策均可回溯

## 13. 风险与开放问题

| 风险 | 缓解 |
|---|---|
| 天级自主执行的成本失控 | 日预算双层硬顶 + PAUSED 通知 + 累计成本可视 |
| LLM 判官被"说服"（目标投机） | 机械检查优先 + 判官证据强制引用 + 关键目标必须人工审核 |
| 多实例部署调度竞争 | M1 单实例 CAS；M2 演进 SKIP LOCKED（MySQL 8 支持） |
| 段间外部世界变化导致检查点失效 | 种子提示强制"先核对检查点与现实差异再行动"；进度摘要每段刷新 |
| 与绞杀者迁移/共享包演进冲突 | 长任务层仅依赖 agent-engine 公共 SPI，不触前端流式链路 |

**开放问题（需产品决策）**：① 审核超时默认策略（暂停 vs 自动通过）建议=暂停；② 非开发角色是否允许创建长任务（建议=允许，但沙箱按角色限制）；③ 通知渠道（本期站内，邮件/IM 留接口）。
