-- 本地 MCP 服务器（D2-2）：transport ∈ stdio / streamable_http / sse（sse 已弃用仅兼容）
CREATE TABLE IF NOT EXISTS local_mcp_server (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    transport VARCHAR(32) NOT NULL DEFAULT 'stdio',
    command VARCHAR(1024),
    args_json CLOB,
    env_json CLOB,
    url VARCHAR(512),
    enabled INT NOT NULL DEFAULT 1,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
