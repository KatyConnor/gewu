-- V34: 会话增值功能支持
-- pinned: 会话置顶标记（列表排序置顶优先）
ALTER TABLE session
    ADD COLUMN pinned TINYINT NOT NULL DEFAULT 0 COMMENT '置顶: 0否/1是' AFTER is_public,
    ADD INDEX idx_session_pinned (pinned);
