-- 桌面运行时本地存储 V1（ADR-006：H2 文件库，表结构对齐平台 session/session_message 语义）
-- 会话表：字段与平台 session 表语义对齐（S9 五列），同步层按字段搬运
CREATE TABLE IF NOT EXISTS local_session (
    id VARCHAR(64) PRIMARY KEY,
    title VARCHAR(256) NOT NULL DEFAULT '新对话',
    agent_id VARCHAR(64),
    runtime VARCHAR(16) NOT NULL DEFAULT 'local',
    model_provider VARCHAR(64),
    model_name VARCHAR(128),
    workspace_path VARCHAR(1024),
    project_id VARCHAR(64),
    workspace_id VARCHAR(64),
    directory VARCHAR(1024),
    status INT NOT NULL DEFAULT 0,
    pinned INT NOT NULL DEFAULT 0,
    time_archived BIGINT,
    message_count INT NOT NULL DEFAULT 0,
    last_message_at BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_session_proj ON local_session(project_id);
CREATE INDEX IF NOT EXISTS idx_session_status ON local_session(status);

-- 消息表：client_id 幂等（对齐平台 V33 唯一键语义，D-3 修复后的同步契约）
CREATE TABLE IF NOT EXISTS local_message (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    seq INT NOT NULL,
    client_id VARCHAR(64),
    role VARCHAR(16) NOT NULL,
    content CLOB,
    metadata CLOB,
    created_at BIGINT NOT NULL,
    CONSTRAINT uk_msg_seq UNIQUE (session_id, seq),
    CONSTRAINT uk_msg_client UNIQUE (session_id, client_id)
);
CREATE INDEX IF NOT EXISTS idx_msg_session ON local_message(session_id, created_at);

-- Agent 定义（本地库，等价平台 agent 域）
CREATE TABLE IF NOT EXISTS local_agent (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    model_provider VARCHAR(64),
    model_name VARCHAR(128),
    system_prompt CLOB,
    status INT NOT NULL DEFAULT 1,
    config_json CLOB,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

-- 执行记录（对齐引擎 ExecutionRecord；协同模式可上送 /agents/executions）
CREATE TABLE IF NOT EXISTS local_execution (
    id VARCHAR(64) PRIMARY KEY,
    agent_id VARCHAR(64),
    session_id VARCHAR(64),
    user_id VARCHAR(64),
    status VARCHAR(16) NOT NULL,
    input CLOB,
    output CLOB,
    error_message CLOB,
    tokens_used INT,
    started_at BIGINT NOT NULL,
    completed_at BIGINT,
    duration_ms INT
);

-- 本地审计（含哈希链列，P4 上送平台审计链预留）
CREATE TABLE IF NOT EXISTS local_audit (
    id VARCHAR(64) PRIMARY KEY,
    tool VARCHAR(128) NOT NULL,
    action VARCHAR(32) NOT NULL,
    target VARCHAR(512),
    decision VARCHAR(16),
    operator VARCHAR(64),
    detail CLOB,
    prev_hash VARCHAR(64),
    hash VARCHAR(64),
    created_at BIGINT NOT NULL
);

-- 本地用量（协同模式批量上送 /desktop/usage）
CREATE TABLE IF NOT EXISTS local_usage (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64),
    provider VARCHAR(64),
    model VARCHAR(128),
    prompt_tokens BIGINT,
    completion_tokens BIGINT,
    cost_yuan DECIMAL(12, 6),
    created_at BIGINT NOT NULL
);

-- 本地模型供应商配置（Key 不入库——经 KeyVault 内存注入，见 DesktopKeyVault）
CREATE TABLE IF NOT EXISTS local_model_provider (
    code VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(128),
    base_url VARCHAR(512) NOT NULL,
    models_json CLOB NOT NULL,
    enabled INT NOT NULL DEFAULT 1,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS kv_store (
    k VARCHAR(128) PRIMARY KEY,
    v CLOB
);
