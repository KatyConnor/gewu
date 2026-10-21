-- V77: 持久化目标驱动长任务（docs/design/45）：小时级～按天、目标达标即止、人工审核闭环

CREATE TABLE IF NOT EXISTS agent_long_task (
    id               VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    session_id       VARCHAR(26) NOT NULL COMMENT '归属会话（消息流/文件树/终端复用）',
    title            VARCHAR(200) NOT NULL COMMENT '任务标题',
    goal_text        TEXT NOT NULL COMMENT '目标声明（自然语言）',
    criteria_json    MEDIUMTEXT NOT NULL COMMENT '验收标准 JSON（statement/checks/review_required/milestone）',
    review_required  TINYINT NOT NULL DEFAULT 1 COMMENT '达标后是否必须人工审核',
    agent_id         VARCHAR(64) DEFAULT NULL COMMENT '段执行使用的智能体（继承会话）',
    provider_code    VARCHAR(64) DEFAULT NULL COMMENT '目标判官 LLM 供应商',
    model            VARCHAR(128) DEFAULT NULL COMMENT '目标判官模型',
    status           VARCHAR(20) NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED/RUNNING/WAITING_REVIEW/PAUSED/BLOCKED/COMPLETED/FAILED/EXPIRED/CANCELLED',
    segment_count    INT NOT NULL DEFAULT 0,
    max_segments     INT NOT NULL DEFAULT 100,
    max_days         INT NOT NULL DEFAULT 7,
    deadline_at      BIGINT NOT NULL,
    cooldown_minutes INT NOT NULL DEFAULT 5,
    daily_token_cap  BIGINT NOT NULL DEFAULT 0 COMMENT '0=不限；按自然日累计',
    total_tokens     BIGINT NOT NULL DEFAULT 0,
    fail_streak      INT NOT NULL DEFAULT 0,
    next_run_at      BIGINT NOT NULL,
    steering_note    TEXT DEFAULT NULL COMMENT '待下一段消费的用户补充指令',
    progress_summary TEXT DEFAULT NULL,
    created_by       VARCHAR(26) NOT NULL,
    deleted TINYINT DEFAULT 0, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_lt_session (session_id),
    KEY idx_lt_status_next (status, next_run_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='持久化目标驱动长任务';

CREATE TABLE IF NOT EXISTS agent_long_task_segment (
    id           VARCHAR(26) NOT NULL,
    task_id      VARCHAR(26) NOT NULL,
    seq          INT NOT NULL,
    status       VARCHAR(20) NOT NULL COMMENT 'running/success/failed/blocked/interrupted',
    seed_prompt  TEXT NOT NULL COMMENT '本段种子提示',
    summary      TEXT DEFAULT NULL COMMENT '段结束摘要',
    checkpoint   MEDIUMTEXT NOT NULL COMMENT '检查点 JSON（进度/计划/文件清单/下一步）',
    goal_eval    TEXT DEFAULT NULL COMMENT '目标评估结果 JSON',
    tokens       BIGINT NOT NULL DEFAULT 0,
    error_msg    VARCHAR(500) DEFAULT NULL,
    started_at   BIGINT NOT NULL, finished_at BIGINT DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_seg (task_id, seq),
    KEY idx_seg_task (task_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='长任务分段执行记录';

CREATE TABLE IF NOT EXISTS agent_long_task_review (
    id          VARCHAR(26) NOT NULL,
    task_id     VARCHAR(26) NOT NULL,
    segment_seq INT NOT NULL,
    kind        VARCHAR(20) NOT NULL COMMENT 'goal_reached/milestone/manual/blocked',
    verdict     VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'pending/approved/rejected',
    comment     TEXT DEFAULT NULL,
    reviewer    VARCHAR(26) DEFAULT NULL,
    created_at  BIGINT NOT NULL, decided_at BIGINT DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_review_task (task_id, verdict)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='长任务人工审核';
