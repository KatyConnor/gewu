-- ============================================================================
-- V83: 长任务三表补齐 BaseEntity 审计列
--   V77 建表时三张表实体（AgentLongTask/AgentLongTaskSegment/AgentLongTaskReview）
--   均继承 BaseEntity（id/deleted/createdAt/updatedAt/createdBy/updatedBy），
--   但建表列不全 → MyBatis-Plus 全列 SELECT/INSERT 报 Unknown column
--   （agent_long_task 轮询每周期报错，日志暴涨，2026-10-24 定位）。
--   按实体映射补齐缺失列；已存在列不动。
-- ============================================================================

-- 1. agent_long_task：缺 updated_by
ALTER TABLE agent_long_task
    ADD COLUMN updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人' AFTER created_by;

-- 2. agent_long_task_segment：缺 BaseEntity 全部审计列
ALTER TABLE agent_long_task_segment
    ADD COLUMN deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    ADD COLUMN created_at BIGINT DEFAULT NULL COMMENT '创建时间',
    ADD COLUMN updated_at BIGINT DEFAULT NULL COMMENT '更新时间',
    ADD COLUMN created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人',
    ADD COLUMN updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人';

-- 3. agent_long_task_review：缺 deleted/updated_at/created_by/updated_by（created_at 已有）
ALTER TABLE agent_long_task_review
    ADD COLUMN deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    ADD COLUMN updated_at BIGINT DEFAULT NULL COMMENT '更新时间',
    ADD COLUMN created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人',
    ADD COLUMN updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人';
