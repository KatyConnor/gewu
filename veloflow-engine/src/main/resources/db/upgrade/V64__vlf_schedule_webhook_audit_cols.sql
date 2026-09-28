-- ============================================================
-- V64: VLF schedule/webhook 表补 BaseEntity 审计列
-- （P2 冒烟发现项：两表建表时遗漏 created_by/updated_by/deleted，
--   实体继承 BaseEntity 后 INSERT 携带这些列报 Unknown column）
-- ============================================================

ALTER TABLE VLF_WORKFLOW_SCHEDULE
    ADD COLUMN created_by VARCHAR(26) DEFAULT NULL,
    ADD COLUMN updated_by VARCHAR(26) DEFAULT NULL,
    ADD COLUMN deleted TINYINT DEFAULT 0;

ALTER TABLE VLF_WORKFLOW_WEBHOOK
    ADD COLUMN created_by VARCHAR(26) DEFAULT NULL,
    ADD COLUMN updated_by VARCHAR(26) DEFAULT NULL,
    ADD COLUMN deleted TINYINT DEFAULT 0;
