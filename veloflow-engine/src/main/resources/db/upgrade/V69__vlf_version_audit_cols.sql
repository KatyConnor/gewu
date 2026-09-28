-- ============================================================
-- V69: VLF_WORKFLOW_VERSION 补 BaseEntity 审计列（版本快照实体启用，
--   与 V64 schedule/webhook 表同先例），实例绑定版本快照
-- 与 gewu-interface db/migration/V68 同基线
-- ============================================================

ALTER TABLE VLF_WORKFLOW_VERSION
    ADD COLUMN created_by VARCHAR(26) DEFAULT NULL,
    ADD COLUMN updated_by VARCHAR(26) DEFAULT NULL,
    ADD COLUMN deleted TINYINT DEFAULT 0;
