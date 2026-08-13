-- ============================================================
-- V27: 失败案例库
-- 记录任务失败的结构化案例，供后续任务检索复用与规避。
-- ============================================================

CREATE TABLE IF NOT EXISTS failure_case (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    task_type VARCHAR(64) NOT NULL COMMENT '任务类型',
    context_summary TEXT NOT NULL COMMENT '任务上下文摘要',
    failure_point VARCHAR(256) NOT NULL COMMENT '失败步骤',
    root_cause TEXT NOT NULL COMMENT '归因分析',
    lesson TEXT NOT NULL COMMENT '经验教训',
    avoidance_rule TEXT NOT NULL COMMENT '规避规则（可编码为路由规则）',
    confidence FLOAT DEFAULT 0.5 COMMENT '置信度',
    occurrence_count INT DEFAULT 1 COMMENT '出现次数',
    agent_id VARCHAR(26) DEFAULT NULL COMMENT '关联 Agent ID',
    execution_id VARCHAR(26) DEFAULT NULL COMMENT '关联执行实例 ID',
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_failure_task_type (task_type),
    KEY idx_failure_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='失败案例库';