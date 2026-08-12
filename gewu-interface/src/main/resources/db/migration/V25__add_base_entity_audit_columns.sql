-- ============================================================
-- V25: 补全 BaseEntity 审计列
-- 问题：phase_document_version、agent_execution、sandbox_config 三张表
--       的实体类继承 BaseEntity（含 deleted/created_by/updated_by），
--       但建表 SQL（V7/V8 等）遗漏了这三个审计列，
--       导致 INSERT 时 AuditMetaObjectHandler 填充字段报错：
--       "Unknown column 'deleted' in 'field list'"
-- 修复：为三张表补全 deleted/created_by/updated_by 列。
-- ============================================================

-- phase_document_version（V7 建表遗漏）
ALTER TABLE phase_document_version ADD COLUMN deleted TINYINT DEFAULT 0 COMMENT '逻辑删除';
ALTER TABLE phase_document_version ADD COLUMN created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人';
ALTER TABLE phase_document_version ADD COLUMN updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人';

-- agent_execution
ALTER TABLE agent_execution ADD COLUMN deleted TINYINT DEFAULT 0 COMMENT '逻辑删除';
ALTER TABLE agent_execution ADD COLUMN created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人';
ALTER TABLE agent_execution ADD COLUMN updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人';

-- sandbox_config
ALTER TABLE sandbox_config ADD COLUMN deleted TINYINT DEFAULT 0 COMMENT '逻辑删除';
ALTER TABLE sandbox_config ADD COLUMN created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人';
ALTER TABLE sandbox_config ADD COLUMN updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人';
