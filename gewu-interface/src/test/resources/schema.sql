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
    org_id VARCHAR(26) DEFAULT NULL,
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
    data_scope TINYINT DEFAULT 4,
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
    project_code VARCHAR(64) DEFAULT NULL,
    description CLOB DEFAULT NULL,
    visibility TINYINT DEFAULT 0,
    status TINYINT DEFAULT 1,
    owner_id VARCHAR(26) NOT NULL,
    tech_stack VARCHAR(512) DEFAULT NULL,
    worktree VARCHAR(1024) DEFAULT NULL,
    vcs VARCHAR(32) DEFAULT NULL,
    icon_url VARCHAR(512) DEFAULT NULL,
    icon_color VARCHAR(16) DEFAULT NULL,
    time_initialized BIGINT DEFAULT NULL,
    sandboxes CLOB DEFAULT NULL,
    commands CLOB DEFAULT NULL,
    current_phase VARCHAR(32) DEFAULT 'RESEARCH',
    initiated_at BIGINT DEFAULT NULL,
    closed_at BIGINT DEFAULT NULL,
    repo_url VARCHAR(512) DEFAULT NULL,
    repo_branch VARCHAR(128) DEFAULT 'main',
    clone_status VARCHAR(32) DEFAULT NULL,
    repo_local_path VARCHAR(256) DEFAULT NULL,
    head_commit VARCHAR(64) DEFAULT NULL,
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
    pinned TINYINT DEFAULT 0,
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
    experiment_group VARCHAR(32) DEFAULT NULL,
    started_at BIGINT DEFAULT NULL,
    completed_at BIGINT DEFAULT NULL,
    duration_ms INT DEFAULT NULL,
    deleted INT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
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

CREATE TABLE IF NOT EXISTS project_phase (
    id VARCHAR(26) NOT NULL,
    project_id VARCHAR(26) NOT NULL,
    phase_code VARCHAR(32) NOT NULL,
    phase_order INT NOT NULL,
    status TINYINT DEFAULT 0,
    started_at BIGINT DEFAULT NULL,
    completed_at BIGINT DEFAULT NULL,
    agent_id VARCHAR(26) DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS phase_document (
    id VARCHAR(26) NOT NULL,
    project_id VARCHAR(26) NOT NULL,
    phase_code VARCHAR(32) NOT NULL,
    doc_name VARCHAR(256) NOT NULL,
    doc_type VARCHAR(32) DEFAULT NULL,
    current_version INT DEFAULT 1,
    total_versions INT DEFAULT 1,
    review_status VARCHAR(32) DEFAULT 'draft',
    reviewed_by VARCHAR(26) DEFAULT NULL,
    reviewed_at BIGINT DEFAULT NULL,
    review_comment CLOB DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS phase_document_version (
    id VARCHAR(26) NOT NULL,
    document_id VARCHAR(26) NOT NULL,
    version_no INT NOT NULL,
    file_url VARCHAR(1024) DEFAULT NULL,
    content_md5 VARCHAR(64) DEFAULT NULL,
    change_summary VARCHAR(512) DEFAULT NULL,
    change_source VARCHAR(32) NOT NULL,
    agent_id VARCHAR(26) DEFAULT NULL,
    agent_prompt CLOB DEFAULT NULL,
    file_size BIGINT DEFAULT NULL,
    uploaded_by VARCHAR(26) DEFAULT NULL,
    uploaded_at BIGINT DEFAULT NULL,
    deleted INT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS requirement (
    id VARCHAR(26) NOT NULL,
    requirement_code VARCHAR(64) DEFAULT NULL,
    title VARCHAR(256) NOT NULL,
    description CLOB DEFAULT NULL,
    type VARCHAR(32) NOT NULL,
    priority TINYINT DEFAULT 2,
    status VARCHAR(32) DEFAULT 'DRAFT',
    assignee_id VARCHAR(26) DEFAULT NULL,
    reporter_id VARCHAR(26) DEFAULT NULL,
    designer_id VARCHAR(26) DEFAULT NULL,
    developer_id VARCHAR(26) DEFAULT NULL,
    tester_id VARCHAR(26) DEFAULT NULL,
    parent_id VARCHAR(26) DEFAULT NULL,
    project_id VARCHAR(26) DEFAULT NULL,
    session_ids VARCHAR(512) DEFAULT NULL,
    document_ids VARCHAR(512) DEFAULT NULL,
    design_doc VARCHAR(1024) DEFAULT NULL,
    plan_doc VARCHAR(1024) DEFAULT NULL,
    test_doc VARCHAR(1024) DEFAULT NULL,
    story_point INT DEFAULT NULL,
    estimated_hours INT DEFAULT NULL,
    actual_hours INT DEFAULT NULL,
    due_date BIGINT DEFAULT NULL,
    started_at BIGINT DEFAULT NULL,
    completed_at BIGINT DEFAULT NULL,
    released_at BIGINT DEFAULT NULL,
    cancelled_at BIGINT DEFAULT NULL,
    cancel_reason CLOB DEFAULT NULL,
    git_branch VARCHAR(128) DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS requirement_review (
    id VARCHAR(26) NOT NULL,
    requirement_id VARCHAR(26) NOT NULL,
    review_type VARCHAR(32) NOT NULL,
    reviewer_id VARCHAR(26) NOT NULL,
    review_result VARCHAR(32) DEFAULT NULL,
    review_comment CLOB DEFAULT NULL,
    review_attachments VARCHAR(1024) DEFAULT NULL,
    review_order INT DEFAULT 0,
    completed_at BIGINT DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);

-- 以下为 V21 迁移新增表（H2 测试环境补齐）
CREATE TABLE IF NOT EXISTS requirement_file (
    id VARCHAR(26) NOT NULL,
    requirement_id VARCHAR(26) NOT NULL,
    category VARCHAR(32) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    mime_type VARCHAR(128) DEFAULT NULL,
    file_size BIGINT DEFAULT 0,
    version INT NOT NULL DEFAULT 1,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS requirement_task (
    id VARCHAR(26) NOT NULL,
    requirement_id VARCHAR(26) NOT NULL,
    task_code VARCHAR(64) DEFAULT NULL,
    title VARCHAR(256) NOT NULL,
    description CLOB DEFAULT NULL,
    assignee_id VARCHAR(26) DEFAULT NULL,
    status VARCHAR(32) DEFAULT 'PENDING',
    estimated_hours INT DEFAULT NULL,
    actual_hours INT DEFAULT NULL,
    started_at BIGINT DEFAULT NULL,
    completed_at BIGINT DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS requirement_comment (
    id VARCHAR(26) NOT NULL,
    requirement_id VARCHAR(26) NOT NULL,
    content CLOB NOT NULL,
    parent_id VARCHAR(26) DEFAULT NULL,
    attachments VARCHAR(1024) DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);
-- 以下为 V9/V10/V11 迁移新增表（H2 测试环境补齐）
CREATE TABLE IF NOT EXISTS agent_market (
    id VARCHAR(26) NOT NULL, agent_id VARCHAR(26) NOT NULL,
    agent_name VARCHAR(128) NOT NULL, description VARCHAR(1024),
    model_provider VARCHAR(64) NOT NULL, model_name VARCHAR(128) NOT NULL,
    model_config CLOB, system_prompt CLOB,
    emoji VARCHAR(16) DEFAULT '🤖', category VARCHAR(64), tags CLOB,
    stars INT DEFAULT 0, install_count INT DEFAULT 0, author VARCHAR(128),
    status TINYINT DEFAULT 1, version INT DEFAULT 0,
    deleted TINYINT DEFAULT 0, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) NOT NULL, updated_by VARCHAR(26),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS skill (
    id VARCHAR(26) NOT NULL, skill_name VARCHAR(128) NOT NULL,
    description VARCHAR(1024), category VARCHAR(64), content CLOB,
    emoji VARCHAR(16) DEFAULT '⚡', tags CLOB, install_count INT DEFAULT 0,
    status TINYINT DEFAULT 1, version INT DEFAULT 0, publish_status TINYINT DEFAULT 0,
    deleted TINYINT DEFAULT 0, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
    created_by VARCHAR(26), updated_by VARCHAR(26),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS agent_skill (
    id VARCHAR(26) NOT NULL, agent_id VARCHAR(26) NOT NULL, skill_id VARCHAR(26) NOT NULL,
    sort_order INT DEFAULT 0,
    deleted TINYINT DEFAULT 0, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
    created_by VARCHAR(26), updated_by VARCHAR(26),
    PRIMARY KEY (id)
);

-- 以下为 V15 迁移新增表（H2 测试环境补齐）
CREATE TABLE IF NOT EXISTS menu (
    id VARCHAR(26) NOT NULL,
    parent_id VARCHAR(26) DEFAULT NULL,
    menu_name VARCHAR(64) NOT NULL,
    menu_type TINYINT DEFAULT 2,
    path VARCHAR(128) DEFAULT NULL,
    icon VARCHAR(32) DEFAULT NULL,
    sort_order INT DEFAULT 0,
    permission_code VARCHAR(128) DEFAULT NULL,
    visible TINYINT DEFAULT 1,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS role_menu (
    id VARCHAR(26) NOT NULL,
    role_id VARCHAR(26) NOT NULL,
    menu_id VARCHAR(26) NOT NULL,
    created_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);

-- 以下为 V16 迁移新增表（H2 测试环境补齐）
CREATE TABLE IF NOT EXISTS organization (
    id VARCHAR(26) NOT NULL,
    parent_id VARCHAR(26) DEFAULT NULL,
    org_name VARCHAR(128) NOT NULL,
    org_code VARCHAR(64) NOT NULL,
    sort_order INT DEFAULT 0,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);

-- 以下为 V19 迁移新增表（H2 测试环境补齐）
CREATE TABLE IF NOT EXISTS workspace (
    id VARCHAR(26) NOT NULL,
    user_id VARCHAR(26) NOT NULL,
    workspace_name VARCHAR(128) NOT NULL DEFAULT '我的工作空间',
    storage_path VARCHAR(256) NOT NULL,
    quota_bytes BIGINT NOT NULL DEFAULT 1073741824,
    used_bytes BIGINT NOT NULL DEFAULT 0,
    file_count INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    mode VARCHAR(16) NOT NULL DEFAULT 'storage',
    dev_sandbox_id VARCHAR(26) DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_workspace_user UNIQUE (user_id)
);

CREATE TABLE IF NOT EXISTS workspace_file (
    id VARCHAR(26) NOT NULL,
    workspace_id VARCHAR(26) NOT NULL,
    parent_id VARCHAR(26) DEFAULT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_type TINYINT NOT NULL,
    file_path VARCHAR(1024) NOT NULL,
    object_key VARCHAR(512) DEFAULT NULL,
    mime_type VARCHAR(128) DEFAULT NULL,
    file_size BIGINT DEFAULT 0,
    checksum VARCHAR(64) DEFAULT NULL,
    version INT NOT NULL DEFAULT 1,
    status TINYINT NOT NULL DEFAULT 1,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);

-- 以下为 V20 迁移新增表（H2 测试环境补齐）
CREATE TABLE IF NOT EXISTS workspace_project (
    id VARCHAR(26) NOT NULL,
    workspace_id VARCHAR(26) NOT NULL,
    project_name VARCHAR(128) NOT NULL,
    repo_url VARCHAR(512) NOT NULL,
    repo_branch VARCHAR(128) DEFAULT 'main',
    local_path VARCHAR(256) NOT NULL,
    clone_status VARCHAR(32) DEFAULT 'pending',
    last_sync_at BIGINT DEFAULT NULL,
    head_commit VARCHAR(64) DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS git_credential (
    id VARCHAR(26) NOT NULL,
    user_id VARCHAR(26) NOT NULL,
    cred_name VARCHAR(64) NOT NULL,
    cred_type VARCHAR(16) NOT NULL,
    cred_value TEXT NOT NULL,
    ssh_public_key TEXT DEFAULT NULL,
    git_host VARCHAR(256) DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);

-- ============================================================
-- Veloflow 流程引擎表（H2 测试版，52 号 P1 平台切换）
-- 与 veloflow_init.sql 基线一致：JSON/LONGTEXT → CLOB（H2 MODE=MySQL）
-- ============================================================
CREATE TABLE IF NOT EXISTS VLF_WORKFLOW (
    id VARCHAR(26) NOT NULL,
    workflow_name VARCHAR(128) NOT NULL,
    description CLOB DEFAULT NULL,
    version INT NOT NULL DEFAULT 1,
    status TINYINT DEFAULT 0,
    category VARCHAR(64) DEFAULT NULL,
    config CLOB DEFAULT NULL,
    published_at BIGINT DEFAULT NULL,
    tenant_id VARCHAR(26) DEFAULT 'default',
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);
CREATE TABLE IF NOT EXISTS VLF_WORKFLOW_NODE (
    id VARCHAR(26) NOT NULL,
    workflow_id VARCHAR(26) NOT NULL,
    biz_node_id VARCHAR(64) DEFAULT NULL,
    node_name VARCHAR(128) NOT NULL,
    node_type VARCHAR(32) NOT NULL,
    config CLOB DEFAULT NULL,
    position_x FLOAT DEFAULT NULL,
    position_y FLOAT DEFAULT NULL,
    sort_order INT DEFAULT 0,
    tenant_id VARCHAR(26) DEFAULT 'default',
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);
CREATE TABLE IF NOT EXISTS VLF_WORKFLOW_TRANSITION (
    id VARCHAR(26) NOT NULL,
    workflow_id VARCHAR(26) NOT NULL,
    from_node_id VARCHAR(64) NOT NULL,
    to_node_id VARCHAR(64) NOT NULL,
    condition_expr CLOB DEFAULT NULL,
    label VARCHAR(64) DEFAULT NULL,
    sort_order INT DEFAULT 0,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
CREATE TABLE IF NOT EXISTS VLF_WORKFLOW_INSTANCE (
    id VARCHAR(26) NOT NULL,
    workflow_id VARCHAR(26) NOT NULL,
    workflow_version INT NOT NULL DEFAULT 1,
    version_id VARCHAR(26) DEFAULT NULL,
    title VARCHAR(256) DEFAULT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'running',
    initiator_id VARCHAR(26) NOT NULL,
    trigger_type VARCHAR(32) DEFAULT 'MANUAL',
    current_node_id VARCHAR(64) DEFAULT NULL,
    variables CLOB DEFAULT NULL,
    final_output CLOB DEFAULT NULL,
    error_message CLOB DEFAULT NULL,
    started_at BIGINT NOT NULL,
    completed_at BIGINT DEFAULT NULL,
    tenant_id VARCHAR(26) DEFAULT 'default',
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);
CREATE TABLE IF NOT EXISTS VLF_WORKFLOW_NODE_INSTANCE (
    id VARCHAR(26) NOT NULL,
    instance_id VARCHAR(26) NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    branch_key VARCHAR(64) NOT NULL DEFAULT '',
    iteration INT NOT NULL DEFAULT 0,
    node_name VARCHAR(128) NOT NULL,
    node_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    assignee_id VARCHAR(26) DEFAULT NULL,
    input CLOB DEFAULT NULL,
    output CLOB DEFAULT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    error_message CLOB DEFAULT NULL,
    started_at BIGINT DEFAULT NULL,
    completed_at BIGINT DEFAULT NULL,
    timeout_at BIGINT DEFAULT NULL,
    remark CLOB DEFAULT NULL,
    tenant_id VARCHAR(26) DEFAULT 'default',
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
CREATE TABLE IF NOT EXISTS VLF_WORKFLOW_NOTIFICATION (
    id VARCHAR(26) NOT NULL,
    instance_id VARCHAR(26) NOT NULL,
    node_instance_id VARCHAR(26) DEFAULT NULL,
    type VARCHAR(50) NOT NULL,
    recipient_id VARCHAR(26) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content CLOB DEFAULT NULL,
    is_read TINYINT DEFAULT 0,
    sent_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
CREATE TABLE IF NOT EXISTS VLF_WORKFLOW_AUDIT_LOG (
    id VARCHAR(26) NOT NULL,
    workflow_id VARCHAR(26) NOT NULL,
    instance_id VARCHAR(26) DEFAULT NULL,
    node_id VARCHAR(64) DEFAULT NULL,
    operation VARCHAR(50) NOT NULL,
    operator_id VARCHAR(26) NOT NULL,
    operator_name VARCHAR(50) DEFAULT NULL,
    before_state VARCHAR(20) DEFAULT NULL,
    after_state VARCHAR(20) DEFAULT NULL,
    ip_address VARCHAR(45) DEFAULT NULL,
    request_body CLOB DEFAULT NULL,
    response_code INT DEFAULT NULL,
    response_time BIGINT DEFAULT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
CREATE TABLE IF NOT EXISTS VLF_WORKFLOW_VERSION (
    id VARCHAR(26) NOT NULL,
    workflow_id VARCHAR(26) NOT NULL,
    version INT NOT NULL,
    definition_snapshot CLOB NOT NULL,
    published_by VARCHAR(26) DEFAULT NULL,
    published_at BIGINT DEFAULT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
CREATE TABLE IF NOT EXISTS VLF_WORKFLOW_SCHEDULE (
    id VARCHAR(26) NOT NULL,
    workflow_id VARCHAR(26) NOT NULL,
    cron_expr VARCHAR(64) NOT NULL,
    timezone VARCHAR(64) DEFAULT 'Asia/Shanghai',
    input_template CLOB DEFAULT NULL,
    enabled TINYINT DEFAULT 1,
    last_fire_at BIGINT DEFAULT NULL,
    next_fire_at BIGINT DEFAULT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
CREATE TABLE IF NOT EXISTS VLF_WORKFLOW_WEBHOOK (
    id VARCHAR(26) NOT NULL,
    workflow_id VARCHAR(26) NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    enabled TINYINT DEFAULT 1,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
