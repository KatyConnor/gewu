-- ============================================================
-- V71: VLF_WORKFLOW 补定义版本号列（51 号 T4.4 版本快照）
--   workflow_version：发布一次 +1（首次发布=1），实例发起时绑定
-- 与 gewu-interface db/migration/V70 同基线
-- ============================================================

ALTER TABLE VLF_WORKFLOW
    ADD COLUMN workflow_version INT NOT NULL DEFAULT 1 COMMENT '定义版本号（发布一次+1）' AFTER version;
