-- 本地记忆（D2-10 v1：TF-IDF 关键词检索；v2 演进为本地嵌入向量）
CREATE TABLE IF NOT EXISTS local_memory (
    id VARCHAR(64) PRIMARY KEY,
    domain VARCHAR(64),
    type VARCHAR(32),
    content CLOB NOT NULL,
    tokens_json CLOB,
    created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_memory_domain ON local_memory(domain);
