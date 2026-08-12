-- MCP Server 配置表
CREATE TABLE IF NOT EXISTS mcp_server (
    id VARCHAR(36) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    transport VARCHAR(20) NOT NULL DEFAULT 'stdio',
    command VARCHAR(512),
    args TEXT,
    url VARCHAR(512),
    env TEXT,
    status TINYINT DEFAULT 0,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT,
    created_by VARCHAR(36),
    updated_by VARCHAR(36),
    PRIMARY KEY (id)
);

-- agent_tool 新增 mcp_server_id 字段
ALTER TABLE agent_tool ADD COLUMN mcp_server_id VARCHAR(36) NULL;
