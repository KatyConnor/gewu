-- ============================================================
-- V30: 修复 audit_chain 表缺失的 BaseEntity 审计列
-- 问题：V28 建表遗漏 deleted/updated_at/created_by/updated_by 四列，
--       而 AuditChainEntity 继承 BaseEntity（含 @TableLogic deleted 等字段），
--       导致 INSERT 时 AuditMetaObjectHandler 填充报 Unknown column，
--       SELECT 时 @TableLogic 自动追加 AND deleted=0 同样报错。
-- 修复：补全四列，保持与其他 BaseEntity 表一致的审计列结构。
-- ============================================================

ALTER TABLE audit_chain ADD COLUMN deleted TINYINT DEFAULT 0 COMMENT '逻辑删除';
ALTER TABLE audit_chain ADD COLUMN updated_at BIGINT NOT NULL DEFAULT 0 COMMENT '更新时间';
ALTER TABLE audit_chain ADD COLUMN created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人';
ALTER TABLE audit_chain ADD COLUMN updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人';