-- ============================================================
-- V74: VLF_WORKFLOW_PERMISSION_MATRIX 补 created_by 列
--   （BaseSimpleEntity 含 createdBy，V72 建表遗漏——insert 携带报 Unknown column）
-- 与 gewu-interface db/migration/V73 同基线
-- ============================================================

ALTER TABLE VLF_WORKFLOW_PERMISSION_MATRIX
    ADD COLUMN created_by VARCHAR(26) DEFAULT NULL;
