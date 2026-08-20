-- ============================================================
-- V29: Agent 执行统计表（渐进式权限模型）
-- 记录 Agent 任务成功/失败统计，驱动信任等级 L0-L3 自动升降。
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_stat (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    agent_id VARCHAR(26) NOT NULL COMMENT 'Agent ID',
    total_tasks INT NOT NULL DEFAULT 0 COMMENT '总任务数',
    success_count INT NOT NULL DEFAULT 0 COMMENT '成功任务数',
    success_rate DOUBLE NOT NULL DEFAULT 0 COMMENT '成功率(0-100)',
    trust_level VARCHAR(8) NOT NULL DEFAULT 'L0' COMMENT '信任等级: L0/L1/L2/L3',
    last_executed_at BIGINT DEFAULT NULL COMMENT '最后执行时间',
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_stat_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 执行统计';