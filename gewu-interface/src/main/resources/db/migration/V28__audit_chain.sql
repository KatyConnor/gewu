-- ============================================================
-- V28: WORM 审计链式哈希存储
-- 一次写入多次读取的不可篡改审计存储，使用链式 SHA-256 哈希
-- 确保决策轨迹不可篡改、可独立审查。
-- ============================================================

CREATE TABLE IF NOT EXISTS audit_chain (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型: AGENT_EXECUTE/ORCHESTRATION/GOAL/HITL/CONFLICT',
    execution_id VARCHAR(64) DEFAULT NULL COMMENT '执行实例 ID',
    actor VARCHAR(128) DEFAULT NULL COMMENT '执行者: user/agent/system',
    action VARCHAR(64) DEFAULT NULL COMMENT '动作: create/execute/approve/reject/cancel',
    decision_trace LONGTEXT COMMENT '决策轨迹 JSON',
    agent_collaboration_log LONGTEXT COMMENT '多Agent通信记录 JSON',
    hash_previous VARCHAR(64) COMMENT '前一条记录哈希（链式哈希）',
    hash_current VARCHAR(64) NOT NULL COMMENT '当前记录哈希',
    verified TINYINT DEFAULT 0 COMMENT '是否已校验',
    created_at BIGINT NOT NULL COMMENT '创建时间戳',
    PRIMARY KEY (id),
    KEY idx_audit_chain_exec (execution_id),
    KEY idx_audit_chain_event (event_type),
    KEY idx_audit_chain_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WORM 审计链（不可篡改）';