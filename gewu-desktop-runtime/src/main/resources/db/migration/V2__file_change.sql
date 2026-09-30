-- 文件变更追踪（V2，对齐平台 V39 语义：会话 × 路径 唯一，首改前基线快照）
CREATE TABLE IF NOT EXISTS local_file_change (
    session_id VARCHAR(64) NOT NULL,
    file_path VARCHAR(1024) NOT NULL,
    change_type VARCHAR(16) NOT NULL,
    before_snapshot CLOB,
    additions INT NOT NULL DEFAULT 0,
    deletions INT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (session_id, file_path)
);
