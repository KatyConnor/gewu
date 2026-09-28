-- ============================================================
-- V59: VLF transition/permission 表补 BaseEntity 审计列
-- （P1 冒烟发现项：迁移后的实体继承 BaseEntity，INSERT 携带
--   created_by/updated_by/deleted/updated_at，原表缺列报 Unknown column）
-- ============================================================

ALTER TABLE VLF_WORKFLOW_TRANSITION
    ADD COLUMN updated_at BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN created_by VARCHAR(26) DEFAULT NULL,
    ADD COLUMN updated_by VARCHAR(26) DEFAULT NULL,
    ADD COLUMN deleted TINYINT DEFAULT 0;

ALTER TABLE VLF_WORKFLOW_PERMISSION
    ADD COLUMN created_by VARCHAR(26) DEFAULT NULL,
    ADD COLUMN updated_by VARCHAR(26) DEFAULT NULL;
