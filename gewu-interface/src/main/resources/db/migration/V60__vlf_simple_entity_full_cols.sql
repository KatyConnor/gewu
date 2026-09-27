-- ============================================================
-- V60: VLF notification/audit_log 表补 BaseEntity 全量审计列
-- （P1 冒烟发现项：两表原为简化列集，实体继承 BaseEntity 后
--   INSERT 携带 updated_at/updated_by/deleted 报 Unknown column）
-- ============================================================

ALTER TABLE VLF_WORKFLOW_NOTIFICATION
    ADD COLUMN created_by VARCHAR(26) DEFAULT NULL,
    ADD COLUMN updated_by VARCHAR(26) DEFAULT NULL,
    ADD COLUMN deleted TINYINT DEFAULT 0;

ALTER TABLE VLF_WORKFLOW_AUDIT_LOG
    ADD COLUMN created_by VARCHAR(26) DEFAULT NULL,
    ADD COLUMN updated_by VARCHAR(26) DEFAULT NULL,
    ADD COLUMN deleted TINYINT DEFAULT 0;
