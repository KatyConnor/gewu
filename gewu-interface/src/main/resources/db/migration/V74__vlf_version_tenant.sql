-- ============================================================
-- V74: VLF_WORKFLOW_VERSION 补 tenant_id 列（核对脚本发现漂移——
--   init 基线含 tenant_id 而 V68 审计列迁移未覆盖）
-- 与 veloflow-engine db/upgrade/V75 同基线
-- ============================================================

ALTER TABLE VLF_WORKFLOW_VERSION
    ADD COLUMN tenant_id VARCHAR(26) DEFAULT 'default';
