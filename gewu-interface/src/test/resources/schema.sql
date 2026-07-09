CREATE TABLE IF NOT EXISTS user_account (
    id VARCHAR(26) NOT NULL,
    username VARCHAR(64) NOT NULL,
    email VARCHAR(128) NOT NULL,
    phone VARCHAR(20) DEFAULT NULL,
    password_hash VARCHAR(256) NOT NULL,
    password_salt VARCHAR(64) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    avatar_url VARCHAR(512) DEFAULT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    last_login_at BIGINT DEFAULT NULL,
    last_login_ip VARCHAR(45) DEFAULT NULL,
    login_fail_count INT DEFAULT 0,
    locked_until BIGINT DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    version INT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_username UNIQUE (username),
    CONSTRAINT uk_user_email UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS role (
    id VARCHAR(26) NOT NULL,
    role_name VARCHAR(64) NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    description VARCHAR(512) DEFAULT NULL,
    is_system TINYINT DEFAULT 0,
    sort_order INT DEFAULT 0,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_role_code UNIQUE (role_code)
);

CREATE TABLE IF NOT EXISTS permission (
    id VARCHAR(26) NOT NULL,
    permission_code VARCHAR(128) NOT NULL,
    permission_name VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    description VARCHAR(512) DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_permission_code UNIQUE (permission_code)
);

CREATE TABLE IF NOT EXISTS user_role (
    id VARCHAR(26) NOT NULL,
    user_id VARCHAR(26) NOT NULL,
    role_id VARCHAR(26) NOT NULL,
    source VARCHAR(32) DEFAULT 'system',
    source_id VARCHAR(26) DEFAULT NULL,
    created_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS role_permission (
    id VARCHAR(26) NOT NULL,
    role_id VARCHAR(26) NOT NULL,
    permission_id VARCHAR(26) NOT NULL,
    created_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS project (
    id VARCHAR(26) NOT NULL,
    project_name VARCHAR(128) NOT NULL,
    description CLOB DEFAULT NULL,
    visibility TINYINT DEFAULT 0,
    status TINYINT DEFAULT 1,
    owner_id VARCHAR(26) NOT NULL,
    tech_stack CLOB DEFAULT NULL,
    worktree VARCHAR(1024) DEFAULT NULL,
    vcs VARCHAR(32) DEFAULT NULL,
    icon_url VARCHAR(512) DEFAULT NULL,
    icon_color VARCHAR(16) DEFAULT NULL,
    time_initialized BIGINT DEFAULT NULL,
    sandboxes CLOB DEFAULT NULL,
    commands CLOB DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    version INT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) NOT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS project_member (
    id VARCHAR(26) NOT NULL,
    project_id VARCHAR(26) NOT NULL,
    user_id VARCHAR(26) NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    joined_at BIGINT NOT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_project_member UNIQUE (project_id, user_id)
);

CREATE TABLE IF NOT EXISTS session (
    id VARCHAR(26) NOT NULL,
    title VARCHAR(256) DEFAULT NULL,
    type TINYINT NOT NULL DEFAULT 1,
    project_id VARCHAR(26) DEFAULT NULL,
    status TINYINT DEFAULT 1,
    is_public TINYINT DEFAULT 0,
    last_message_at BIGINT DEFAULT NULL,
    message_count INT DEFAULT 0,
    parent_id VARCHAR(26) DEFAULT NULL,
    agent VARCHAR(128) DEFAULT NULL,
    model CLOB DEFAULT NULL,
    share_url VARCHAR(512) DEFAULT NULL,
    slug VARCHAR(128) DEFAULT NULL,
    directory VARCHAR(1024) DEFAULT NULL,
    path VARCHAR(1024) DEFAULT NULL,
    workspace_id VARCHAR(26) DEFAULT NULL,
    metadata CLOB DEFAULT NULL,
    cost DECIMAL(10,4) DEFAULT 0,
    tokens_input INT DEFAULT 0,
    tokens_output INT DEFAULT 0,
    tokens_reasoning INT DEFAULT 0,
    tokens_cache_read INT DEFAULT 0,
    tokens_cache_write INT DEFAULT 0,
    summary_additions INT DEFAULT NULL,
    summary_deletions INT DEFAULT NULL,
    summary_files INT DEFAULT NULL,
    summary_diffs CLOB DEFAULT NULL,
    revert CLOB DEFAULT NULL,
    version_tag VARCHAR(32) DEFAULT NULL,
    time_compacting BIGINT DEFAULT NULL,
    time_archived BIGINT DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    version INT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) NOT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS session_member (
    id VARCHAR(26) NOT NULL,
    session_id VARCHAR(26) NOT NULL,
    user_id VARCHAR(26) NOT NULL,
    role TINYINT DEFAULT 0,
    last_read_at BIGINT DEFAULT NULL,
    is_muted TINYINT DEFAULT 0,
    joined_at BIGINT NOT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_session_member UNIQUE (session_id, user_id)
);

CREATE TABLE IF NOT EXISTS session_message (
    id VARCHAR(26) NOT NULL,
    session_id VARCHAR(26) NOT NULL,
    sender_id VARCHAR(26) NOT NULL,
    message_type VARCHAR(32) NOT NULL DEFAULT 'text',
    content CLOB NOT NULL,
    metadata CLOB DEFAULT NULL,
    reply_to VARCHAR(26) DEFAULT NULL,
    mention_user_ids CLOB DEFAULT NULL,
    client_id VARCHAR(64) DEFAULT NULL,
    seq INT DEFAULT NULL,
    edited TINYINT DEFAULT 0,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_session_message_seq UNIQUE (session_id, seq)
);

CREATE TABLE IF NOT EXISTS agent (
    id VARCHAR(26) NOT NULL,
    agent_name VARCHAR(128) NOT NULL,
    description VARCHAR(1024) DEFAULT NULL,
    model_provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    model_config CLOB DEFAULT NULL,
    system_prompt CLOB DEFAULT NULL,
    status TINYINT DEFAULT 1,
    version INT DEFAULT 0,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) NOT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS agent_tool (
    id VARCHAR(26) NOT NULL,
    agent_id VARCHAR(26) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    description VARCHAR(1024) DEFAULT NULL,
    tool_type VARCHAR(64) NOT NULL,
    endpoint VARCHAR(512) DEFAULT NULL,
    request_schema CLOB DEFAULT NULL,
    response_schema CLOB DEFAULT NULL,
    auth_config CLOB DEFAULT NULL,
    timeout_ms INT DEFAULT 30000,
    status TINYINT DEFAULT 1,
    sort_order INT DEFAULT 0,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS agent_execution (
    id VARCHAR(26) NOT NULL,
    agent_id VARCHAR(26) NOT NULL,
    session_id VARCHAR(26) DEFAULT NULL,
    user_id VARCHAR(26) NOT NULL,
    status VARCHAR(32) NOT NULL,
    input CLOB NOT NULL,
    output CLOB DEFAULT NULL,
    error_message CLOB DEFAULT NULL,
    tool_calls CLOB DEFAULT NULL,
    tokens_used INT DEFAULT NULL,
    started_at BIGINT DEFAULT NULL,
    completed_at BIGINT DEFAULT NULL,
    duration_ms INT DEFAULT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS workflow (
    id VARCHAR(26) NOT NULL,
    workflow_name VARCHAR(128) NOT NULL,
    description CLOB DEFAULT NULL,
    version INT NOT NULL DEFAULT 1,
    status TINYINT DEFAULT 0,
    category VARCHAR(64) DEFAULT NULL,
    config CLOB DEFAULT NULL,
    published_at BIGINT DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS workflow_node (
    id VARCHAR(26) NOT NULL,
    workflow_id VARCHAR(26) NOT NULL,
    node_name VARCHAR(128) NOT NULL,
    node_type VARCHAR(32) NOT NULL,
    config CLOB DEFAULT NULL,
    position_x FLOAT DEFAULT NULL,
    position_y FLOAT DEFAULT NULL,
    sort_order INT DEFAULT 0,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);