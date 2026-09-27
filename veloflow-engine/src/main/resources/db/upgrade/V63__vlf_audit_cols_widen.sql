-- ============================================================
-- V63: 审计列扩容（52 号 P2 冒烟发现项）
-- before/after_state 原 VARCHAR(20) 装不下 ULID 用户 ID（26 字符），
-- delegate/review 审计写入必超长。扩至 VARCHAR(64)。
-- ============================================================

ALTER TABLE VLF_WORKFLOW_AUDIT_LOG
    MODIFY before_state VARCHAR(64) DEFAULT NULL,
    MODIFY after_state VARCHAR(64) DEFAULT NULL;
