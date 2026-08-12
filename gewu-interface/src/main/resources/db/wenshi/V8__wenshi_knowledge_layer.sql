-- ============================================================================
-- Wenshi 知识层 DDL V8 (pgvector 原生版本)
-- 目标数据库: PostgreSQL 16 + pgvector
-- 创建日期: 2026-07-17
-- ============================================================================
-- 说明: 使用 pgvector 原生 vector 类型和 hnsw 索引
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================================================
-- 1. wenshi_semantic_fragment — 语义记忆片段
-- ============================================================================
CREATE TABLE IF NOT EXISTS wenshi_semantic_fragment (
    id VARCHAR(26) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(26) NOT NULL,
    owner_user_id VARCHAR(26) NOT NULL,
    graph_node_id VARCHAR(64),
    content TEXT NOT NULL,
    source VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    confidence DECIMAL(3,2) DEFAULT 1.00,
    embedding vector(384),
    metadata JSON,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_wsf_embedding ON wenshi_semantic_fragment USING hnsw (embedding vector_cosine_ops);

-- ============================================================================
-- 2. wenshi_episodic_event — 情景事件
-- ============================================================================
CREATE TABLE IF NOT EXISTS wenshi_episodic_event (
    id VARCHAR(26) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(26) NOT NULL,
    user_id VARCHAR(26) NOT NULL,
    session_id VARCHAR(64),
    event_type VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    embedding vector(384),
    metadata JSON,
    created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_wee_embedding ON wenshi_episodic_event USING hnsw (embedding vector_cosine_ops);

-- ============================================================================
-- 3. wenshi_procedural_memory — 程序性记忆
-- ============================================================================
CREATE TABLE IF NOT EXISTS wenshi_procedural_memory (
    id VARCHAR(26) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(26) NOT NULL,
    type VARCHAR(16) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    definition JSON NOT NULL,
    embedding vector(384),
    usage_count INT DEFAULT 0,
    success_rate DECIMAL(5,2),
    skill_level INT DEFAULT 1,
    learned_from VARCHAR(64),
    status SMALLINT DEFAULT 1,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_wpm_embedding ON wenshi_procedural_memory USING hnsw (embedding vector_cosine_ops);

-- ============================================================================
-- 4. wenshi_experience — 经验库
-- ============================================================================
CREATE TABLE IF NOT EXISTS wenshi_experience (
    id VARCHAR(26) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(26) NOT NULL,
    scenario_hash VARCHAR(64) NOT NULL,
    scenario TEXT NOT NULL,
    strategy TEXT NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    score DECIMAL(3,2) NOT NULL,
    lesson TEXT,
    embedding vector(384),
    source_task VARCHAR(64),
    hit_count INT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_we_embedding ON wenshi_experience USING hnsw (embedding vector_cosine_ops);

-- ============================================================================
-- 5. wenshi_user_profile — 用户画像（参数化记忆）
-- ============================================================================
CREATE TABLE IF NOT EXISTS wenshi_user_profile (
    id VARCHAR(26) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(26) NOT NULL,
    user_id VARCHAR(26) NOT NULL,
    profile_key VARCHAR(128) NOT NULL,
    profile_value JSON NOT NULL,
    source VARCHAR(32) DEFAULT 'MANUAL',
    updated_at BIGINT NOT NULL,
    UNIQUE (tenant_id, user_id, profile_key)
);

-- ============================================================================
-- 6. wenshi_reasoning_trace — 推理轨迹
-- ============================================================================
CREATE TABLE IF NOT EXISTS wenshi_reasoning_trace (
    id VARCHAR(26) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(26) NOT NULL,
    session_id VARCHAR(64),
    task_id VARCHAR(64) NOT NULL,
    plan_tree JSON,
    trace_steps JSON,
    used_knowledge JSON,
    reused_experience_id VARCHAR(26),
    token_stats JSON,
    reasoning_ms BIGINT,
    from_experience BOOLEAN DEFAULT FALSE,
    created_at BIGINT NOT NULL
);
