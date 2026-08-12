-- 智能体技能关联表(agent_skill) - 多对多，Agent 可挂载多个 Skill
CREATE TABLE IF NOT EXISTS agent_skill (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    agent_id VARCHAR(26) NOT NULL COMMENT '智能体 ID',
    skill_id VARCHAR(26) NOT NULL COMMENT '技能 ID',
    sort_order INT DEFAULT 0 COMMENT '排序（控制注入顺序）',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_skill (agent_id, skill_id),
    KEY idx_agent_skill_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体技能关联表';
