-- 补齐 requirement_review 表缺失的 BaseEntity 审计字段（deleted, updated_at, updated_by）
ALTER TABLE requirement_review ADD COLUMN deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标志：0=未删除 1=已删除';
ALTER TABLE requirement_review ADD COLUMN updated_at BIGINT NOT NULL DEFAULT 0 COMMENT '更新时间，Unix 时间戳（毫秒）';
ALTER TABLE requirement_review ADD COLUMN updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人 ID，关联 user_account 表';
