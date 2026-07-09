-- ============================================================================
-- 格物平台 数据库初始化脚本 V1
-- 目标数据库: OceanBase 4.2+ (MySQL 兼容模式) / MySQL 8.0
-- 兼容: 达梦 8.0 / 人大金仓 8.0
-- 总表数: 32
-- 创建日期: 2026-07-08
-- ============================================================================

-- 数据库由 DBA 预先创建，此处仅建表
-- ============================================================================
-- 4.1 用户与权限域 (User & Permission) — 5 张表
-- ============================================================================

-- 1. user_account — 用户账户表
CREATE TABLE IF NOT EXISTS user_account (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    username VARCHAR(64) NOT NULL COMMENT '用户名',
    email VARCHAR(128) NOT NULL COMMENT '邮箱',
    phone VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    password_hash VARCHAR(256) NOT NULL COMMENT 'SM3 密码哈希',
    password_salt VARCHAR(64) NOT NULL COMMENT '密码盐值',
    display_name VARCHAR(64) NOT NULL COMMENT '显示名称',
    avatar_url VARCHAR(512) DEFAULT NULL COMMENT '头像URL',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=启用 2=禁用 3=锁定',
    last_login_at BIGINT DEFAULT NULL COMMENT '最后登录时间',
    last_login_ip VARCHAR(45) DEFAULT NULL COMMENT '最后登录IP',
    login_fail_count INT DEFAULT 0 COMMENT '登录失败次数',
    locked_until BIGINT DEFAULT NULL COMMENT '锁定截止时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    version INT DEFAULT 0 COMMENT '乐观锁',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人ID',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username),
    UNIQUE KEY uk_user_email (email),
    UNIQUE KEY uk_user_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户账户表';

-- 2. role — 角色表
CREATE TABLE IF NOT EXISTS role (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    role_name VARCHAR(64) NOT NULL COMMENT '角色名称',
    role_code VARCHAR(64) NOT NULL COMMENT '角色编码',
    description VARCHAR(512) DEFAULT NULL COMMENT '角色描述',
    is_system TINYINT DEFAULT 0 COMMENT '是否系统内置',
    sort_order INT DEFAULT 0 COMMENT '排序顺序',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人ID',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色表';

-- 3. permission — 权限表
CREATE TABLE IF NOT EXISTS permission (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    permission_code VARCHAR(128) NOT NULL COMMENT '权限编码',
    permission_name VARCHAR(64) NOT NULL COMMENT '权限名称',
    resource_type VARCHAR(64) NOT NULL COMMENT '资源类型',
    action VARCHAR(64) NOT NULL COMMENT '操作 (CREATE/READ/UPDATE/DELETE/EXECUTE)',
    description VARCHAR(512) DEFAULT NULL COMMENT '权限描述',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人ID',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_permission_code (permission_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='权限表';

-- 4. user_role — 用户角色关联表
CREATE TABLE IF NOT EXISTS user_role (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    user_id VARCHAR(26) NOT NULL COMMENT '用户ID',
    role_id VARCHAR(26) NOT NULL COMMENT '角色ID',
    source VARCHAR(32) DEFAULT 'system' COMMENT 'system/project',
    source_id VARCHAR(26) DEFAULT NULL COMMENT '来源ID (如项目ID)',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人ID',
    PRIMARY KEY (id),
    KEY idx_user_role_user (user_id),
    KEY idx_user_role_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联表';

-- 5. role_permission — 角色权限关联表
CREATE TABLE IF NOT EXISTS role_permission (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    role_id VARCHAR(26) NOT NULL COMMENT '角色ID',
    permission_id VARCHAR(26) NOT NULL COMMENT '权限ID',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人ID',
    PRIMARY KEY (id),
    KEY idx_role_permission_role (role_id),
    KEY idx_role_permission_permission (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关联表';

-- ============================================================================
-- 4.2 项目管理域 (Project) — 3 张表
-- ============================================================================

-- 6. project — 项目表
CREATE TABLE IF NOT EXISTS project (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    project_name VARCHAR(128) NOT NULL COMMENT '项目名称',
    description TEXT DEFAULT NULL COMMENT '项目描述',
    visibility TINYINT DEFAULT 0 COMMENT '0=私有 1=公开',
    status TINYINT DEFAULT 1 COMMENT '1=活跃 2=归档 3=关闭',
    owner_id VARCHAR(26) NOT NULL COMMENT '项目所有者',
    tech_stack JSON DEFAULT NULL COMMENT '技术栈配置',
    worktree VARCHAR(1024) DEFAULT NULL COMMENT '工作树路径 (代码项目定位)',
    vcs VARCHAR(32) DEFAULT NULL COMMENT '版本控制系统类型 (git/svn)',
    icon_url VARCHAR(512) DEFAULT NULL COMMENT '项目图标 URL',
    icon_color VARCHAR(16) DEFAULT NULL COMMENT '图标颜色',
    time_initialized BIGINT DEFAULT NULL COMMENT '初始化时间',
    sandboxes JSON DEFAULT NULL COMMENT '沙箱配置列表',
    commands JSON DEFAULT NULL COMMENT '自定义命令配置',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    version INT DEFAULT 0 COMMENT '乐观锁',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) NOT NULL COMMENT '创建人',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    KEY idx_project_owner (owner_id),
    KEY idx_project_worktree (worktree(768))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目表';

-- 7. project_member — 项目成员表
CREATE TABLE IF NOT EXISTS project_member (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    project_id VARCHAR(26) NOT NULL COMMENT '项目ID',
    user_id VARCHAR(26) NOT NULL COMMENT '用户ID',
    role_code VARCHAR(64) NOT NULL COMMENT '项目内角色',
    joined_at BIGINT NOT NULL COMMENT '加入时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_member (project_id, user_id),
    KEY idx_project_member_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目成员表';

-- 8. project_directory — 项目目录表（OpenCode 新增）
CREATE TABLE IF NOT EXISTS project_directory (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    project_id VARCHAR(26) NOT NULL COMMENT '项目ID',
    directory VARCHAR(700) NOT NULL COMMENT '目录路径',
    type VARCHAR(32) NOT NULL COMMENT '目录类型 (source/build/config/test)',
    strategy VARCHAR(32) DEFAULT NULL COMMENT '监控策略 (watch/ignore/include)',
    time_created BIGINT NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_directory (project_id, directory(700)),
    KEY idx_project_directory_type (project_id, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目目录表';

-- ============================================================================
-- 4.3 会话消息域 (Session & Message) — 6 张表
-- ============================================================================

-- 9. session — 会话表
CREATE TABLE IF NOT EXISTS session (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    title VARCHAR(256) DEFAULT NULL COMMENT '会话标题',
    type TINYINT NOT NULL DEFAULT 1 COMMENT '1=群聊 2=私聊 3=AI辅助',
    project_id VARCHAR(26) DEFAULT NULL COMMENT '关联项目ID',
    status TINYINT DEFAULT 1 COMMENT '1=活跃 2=归档 3=关闭',
    is_public TINYINT DEFAULT 0 COMMENT '是否公开',
    last_message_at BIGINT DEFAULT NULL COMMENT '最后消息时间',
    message_count INT DEFAULT 0 COMMENT '消息总数',
    parent_id VARCHAR(26) DEFAULT NULL COMMENT '父会话ID (会话树/分叉)',
    agent VARCHAR(128) DEFAULT NULL COMMENT '绑定的 Agent 名称',
    model JSON DEFAULT NULL COMMENT '模型配置 (provider/model/params)',
    share_url VARCHAR(512) DEFAULT NULL COMMENT '分享链接',
    slug VARCHAR(128) DEFAULT NULL COMMENT '会话唯一标识',
    directory VARCHAR(1024) DEFAULT NULL COMMENT '关联工作目录',
    path VARCHAR(1024) DEFAULT NULL COMMENT '会话存储路径',
    workspace_id VARCHAR(26) DEFAULT NULL COMMENT '工作区ID',
    metadata JSON DEFAULT NULL COMMENT '扩展元数据',
    cost DECIMAL(10,4) DEFAULT 0 COMMENT '累计调用成本',
    tokens_input INT DEFAULT 0 COMMENT '输入 Token 总数',
    tokens_output INT DEFAULT 0 COMMENT '输出 Token 总数',
    tokens_reasoning INT DEFAULT 0 COMMENT '推理 Token 总数',
    tokens_cache_read INT DEFAULT 0 COMMENT '缓存读取 Token 数',
    tokens_cache_write INT DEFAULT 0 COMMENT '缓存写入 Token 数',
    summary_additions INT DEFAULT NULL COMMENT '代码添加行数',
    summary_deletions INT DEFAULT NULL COMMENT '代码删除行数',
    summary_files INT DEFAULT NULL COMMENT '变更文件数',
    summary_diffs JSON DEFAULT NULL COMMENT '差异摘要',
    revert JSON DEFAULT NULL COMMENT '回滚配置',
    version_tag VARCHAR(32) DEFAULT NULL COMMENT '会话版本标识',
    time_compacting BIGINT DEFAULT NULL COMMENT '上下文压缩时间',
    time_archived BIGINT DEFAULT NULL COMMENT '归档时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    version INT DEFAULT 0 COMMENT '乐观锁',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) NOT NULL COMMENT '创建人ID',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人ID',
    PRIMARY KEY (id),
    KEY idx_session_project (project_id),
    KEY idx_session_agent (agent),
    KEY idx_session_parent (parent_id),
    UNIQUE KEY uk_session_slug (slug)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话表';

-- 10. session_member — 会话成员表
CREATE TABLE IF NOT EXISTS session_member (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    session_id VARCHAR(26) NOT NULL COMMENT '会话ID',
    user_id VARCHAR(26) NOT NULL COMMENT '用户ID',
    role TINYINT DEFAULT 0 COMMENT '0=成员 1=管理员',
    last_read_at BIGINT DEFAULT NULL COMMENT '最后阅读时间',
    is_muted TINYINT DEFAULT 0 COMMENT '是否静音',
    joined_at BIGINT NOT NULL COMMENT '加入时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_member (session_id, user_id),
    KEY idx_session_member_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话成员表';

-- 11. session_message — 会话消息表
CREATE TABLE IF NOT EXISTS session_message (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    session_id VARCHAR(26) NOT NULL COMMENT '会话ID',
    sender_id VARCHAR(26) NOT NULL COMMENT '发送者ID',
    message_type VARCHAR(32) NOT NULL DEFAULT 'text' COMMENT 'text/code/image/file/system/ai',
    content TEXT NOT NULL COMMENT '消息内容 (Markdown)',
    metadata JSON DEFAULT NULL COMMENT '元数据',
    reply_to VARCHAR(26) DEFAULT NULL COMMENT '回复目标消息ID',
    mention_user_ids JSON DEFAULT NULL COMMENT '@提及用户ID列表',
    client_id VARCHAR(64) DEFAULT NULL COMMENT '客户端幂等ID',
    seq INT DEFAULT NULL COMMENT '消息序列号',
    edited TINYINT DEFAULT 0 COMMENT '是否已编辑',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '发送时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    KEY idx_session_message_session (session_id, created_at),
    KEY idx_session_message_sender (sender_id),
    UNIQUE KEY uk_session_message_seq (session_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话消息表';

-- 12. session_input — 会话输入表（OpenCode 新增）
CREATE TABLE IF NOT EXISTS session_input (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    session_id VARCHAR(26) NOT NULL COMMENT '会话ID',
    prompt JSON NOT NULL COMMENT '提示词数据 (messages + tools)',
    delivery VARCHAR(32) NOT NULL COMMENT '交付方式 (steer/queue/resume)',
    admitted_seq INT NOT NULL COMMENT '接收序列号',
    promoted_seq INT DEFAULT NULL COMMENT '提升序列号 (null=待处理)',
    time_created BIGINT NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_session_input_session (session_id, promoted_seq, delivery, admitted_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话输入表';

-- 13. session_context_epoch — 会话上下文纪元表（OpenCode 新增）
CREATE TABLE IF NOT EXISTS session_context_epoch (
    session_id VARCHAR(26) NOT NULL COMMENT '会话ID',
    baseline TEXT NOT NULL COMMENT '基线标识',
    snapshot JSON NOT NULL COMMENT '上下文快照 (系统消息 + 历史摘要)',
    baseline_seq INT NOT NULL COMMENT '基线对应的消息序列号',
    PRIMARY KEY (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话上下文纪元表';

-- 14. part — 消息部分表（OpenCode 新增）
CREATE TABLE IF NOT EXISTS part (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    message_id VARCHAR(26) NOT NULL COMMENT '所属消息ID',
    session_id VARCHAR(26) NOT NULL COMMENT '会话ID (冗余，便于按会话清理)',
    part_type VARCHAR(32) NOT NULL COMMENT '部分类型: text/code/tool_use/tool_result/thinking/image',
    data JSON NOT NULL COMMENT '部分数据 (内容、语言、元数据等)',
    sort_order INT DEFAULT 0 COMMENT '排序顺序',
    time_created BIGINT NOT NULL COMMENT '创建时间',
    time_updated BIGINT DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_part_message (message_id, sort_order),
    KEY idx_part_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息部分表';

-- ============================================================================
-- 4.4 Agent 系统域 (Agent System) — 4 张表
-- ============================================================================

-- 15. agent — Agent 配置表
CREATE TABLE IF NOT EXISTS agent (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    agent_name VARCHAR(128) NOT NULL COMMENT 'Agent 名称',
    description VARCHAR(1024) DEFAULT NULL COMMENT 'Agent 描述',
    model_provider VARCHAR(64) NOT NULL COMMENT '模型提供商 (alibaba/deepseek/openai)',
    model_name VARCHAR(128) NOT NULL COMMENT '模型名称',
    model_config JSON DEFAULT NULL COMMENT '模型参数',
    system_prompt TEXT DEFAULT NULL COMMENT '系统提示词',
    status TINYINT DEFAULT 1 COMMENT '1=启用 2=禁用',
    version INT DEFAULT 0 COMMENT '版本号',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) NOT NULL COMMENT '创建人',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    KEY idx_agent_project (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 配置表';

-- 16. agent_tool — 工具注册表
CREATE TABLE IF NOT EXISTS agent_tool (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    agent_id VARCHAR(26) NOT NULL COMMENT 'Agent ID',
    tool_name VARCHAR(128) NOT NULL COMMENT '工具名称',
    description VARCHAR(1024) DEFAULT NULL COMMENT '工具描述',
    tool_type VARCHAR(64) NOT NULL COMMENT 'api/code/function/workflow',
    endpoint VARCHAR(512) DEFAULT NULL COMMENT 'API 端点',
    request_schema JSON DEFAULT NULL COMMENT '请求 Schema',
    response_schema JSON DEFAULT NULL COMMENT '响应 Schema',
    auth_config JSON DEFAULT NULL COMMENT '认证配置',
    timeout_ms INT DEFAULT 30000 COMMENT '超时时间',
    status TINYINT DEFAULT 1 COMMENT '1=启用 2=禁用',
    sort_order INT DEFAULT 0 COMMENT '排序',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_agent_tool_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工具注册表';

-- 17. agent_permission — Agent 权限表
CREATE TABLE IF NOT EXISTS agent_permission (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    agent_id VARCHAR(26) NOT NULL COMMENT 'Agent ID',
    permission_code VARCHAR(128) NOT NULL COMMENT '权限编码',
    effect VARCHAR(16) NOT NULL DEFAULT 'allow' COMMENT 'allow/deny',
    condition_expr JSON DEFAULT NULL COMMENT '条件表达式',
    priority INT DEFAULT 0 COMMENT '优先级',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人',
    PRIMARY KEY (id),
    KEY idx_agent_permission_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 权限表';

-- 18. agent_execution — Agent 执行记录表
CREATE TABLE IF NOT EXISTS agent_execution (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    agent_id VARCHAR(26) NOT NULL COMMENT 'Agent ID',
    session_id VARCHAR(26) DEFAULT NULL COMMENT '所属会话ID',
    user_id VARCHAR(26) NOT NULL COMMENT '发起人ID',
    status VARCHAR(32) NOT NULL COMMENT 'pending/running/completed/failed/cancelled',
    input JSON NOT NULL COMMENT '输入参数',
    output JSON DEFAULT NULL COMMENT '输出结果',
    error_message TEXT DEFAULT NULL COMMENT '错误信息',
    tool_calls JSON DEFAULT NULL COMMENT '工具调用记录',
    tokens_used INT DEFAULT NULL COMMENT 'Token 消耗',
    started_at BIGINT DEFAULT NULL COMMENT '开始时间',
    completed_at BIGINT DEFAULT NULL COMMENT '完成时间',
    duration_ms INT DEFAULT NULL COMMENT '执行耗时',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_agent_execution_agent (agent_id),
    KEY idx_agent_execution_session (session_id),
    KEY idx_agent_execution_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 执行记录表';

-- ============================================================================
-- 4.5 工作流引擎域 (Workflow) — 9 张表
-- ============================================================================

-- 19. workflow — 工作流模板表
CREATE TABLE IF NOT EXISTS workflow (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    workflow_name VARCHAR(128) NOT NULL COMMENT '工作流名称',
    description TEXT DEFAULT NULL COMMENT '工作流描述',
    version INT NOT NULL DEFAULT 1 COMMENT '版本号',
    status TINYINT DEFAULT 0 COMMENT '0=草稿 1=已发布 2=已归档',
    category VARCHAR(64) DEFAULT NULL COMMENT '分类',
    config JSON DEFAULT NULL COMMENT '工作流配置 (状态机定义)',
    published_at BIGINT DEFAULT NULL COMMENT '发布时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流模板表';

-- 20. workflow_node — 工作流节点表
CREATE TABLE IF NOT EXISTS workflow_node (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    workflow_id VARCHAR(26) NOT NULL COMMENT '工作流ID',
    node_name VARCHAR(128) NOT NULL COMMENT '节点名称',
    node_type VARCHAR(32) NOT NULL COMMENT 'start/end/task/approval/condition/subprocess',
    config JSON DEFAULT NULL COMMENT '节点配置',
    position_x FLOAT DEFAULT NULL COMMENT '画布 X 坐标',
    position_y FLOAT DEFAULT NULL COMMENT '画布 Y 坐标',
    sort_order INT DEFAULT 0 COMMENT '排序',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    KEY idx_workflow_node_workflow (workflow_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流节点表';

-- 21. workflow_transition — 工作流流转表
CREATE TABLE IF NOT EXISTS workflow_transition (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    workflow_id VARCHAR(26) NOT NULL COMMENT '工作流ID',
    from_node_id VARCHAR(26) NOT NULL COMMENT '源节点ID',
    to_node_id VARCHAR(26) NOT NULL COMMENT '目标节点ID',
    condition_expr TEXT DEFAULT NULL COMMENT '流转条件表达式',
    label VARCHAR(64) DEFAULT NULL COMMENT '流转标签',
    sort_order INT DEFAULT 0 COMMENT '排序',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_workflow_transition_workflow (workflow_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流流转表';

-- 22. workflow_instance — 工作流实例表
CREATE TABLE IF NOT EXISTS workflow_instance (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    workflow_id VARCHAR(26) NOT NULL COMMENT '工作流模板ID',
    workflow_version INT NOT NULL COMMENT '使用的版本号',
    title VARCHAR(256) DEFAULT NULL COMMENT '实例标题',
    status VARCHAR(32) NOT NULL COMMENT 'running/completed/failed/suspended/terminated',
    initiator_id VARCHAR(26) NOT NULL COMMENT '发起人ID',
    current_node_id VARCHAR(26) DEFAULT NULL COMMENT '当前节点ID',
    variables JSON DEFAULT NULL COMMENT '流程变量',
    started_at BIGINT NOT NULL COMMENT '开始时间',
    completed_at BIGINT DEFAULT NULL COMMENT '完成时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    KEY idx_workflow_instance_workflow (workflow_id),
    KEY idx_workflow_instance_initiator (initiator_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流实例表';

-- 23. workflow_node_instance — 工作流节点实例表
CREATE TABLE IF NOT EXISTS workflow_node_instance (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    instance_id VARCHAR(26) NOT NULL COMMENT '工作流实例ID',
    node_id VARCHAR(26) NOT NULL COMMENT '节点模板ID',
    node_name VARCHAR(128) NOT NULL COMMENT '节点名称',
    node_type VARCHAR(32) NOT NULL COMMENT '节点类型',
    status VARCHAR(32) NOT NULL COMMENT 'pending/running/completed/failed/skipped',
    assignee_id VARCHAR(26) DEFAULT NULL COMMENT '处理人ID',
    input JSON DEFAULT NULL COMMENT '节点输入',
    output JSON DEFAULT NULL COMMENT '节点输出',
    started_at BIGINT DEFAULT NULL COMMENT '开始时间',
    completed_at BIGINT DEFAULT NULL COMMENT '完成时间',
    remark TEXT DEFAULT NULL COMMENT '处理备注',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    KEY idx_workflow_node_instance_instance (instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流节点实例表';

-- 24. workflow_notification — 工作流通知表
CREATE TABLE IF NOT EXISTS workflow_notification (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    instance_id VARCHAR(26) NOT NULL COMMENT '工作流实例ID',
    node_instance_id VARCHAR(26) DEFAULT NULL COMMENT '节点实例ID',
    type VARCHAR(50) NOT NULL COMMENT 'TASK_CREATED/TASK_ASSIGNED/REVIEW_REQUIRED/TIMEOUT_WARNING',
    recipient_id VARCHAR(26) NOT NULL COMMENT '接收人ID',
    title VARCHAR(200) NOT NULL COMMENT '通知标题',
    content TEXT DEFAULT NULL COMMENT '通知内容',
    is_read TINYINT DEFAULT 0 COMMENT '是否已读',
    sent_at BIGINT NOT NULL COMMENT '发送时间',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_notification_recipient_read (recipient_id, is_read),
    KEY idx_notification_type_sent (type, sent_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流通知表';

-- 25. workflow_permission — 工作流权限表
CREATE TABLE IF NOT EXISTS workflow_permission (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    workflow_id VARCHAR(26) NOT NULL COMMENT '工作流ID',
    role_code VARCHAR(50) NOT NULL COMMENT '角色编码',
    permission_type VARCHAR(20) NOT NULL COMMENT 'START/EXECUTE/REVIEW/MANAGE',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_workflow_role_permission (workflow_id, role_code, permission_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流权限表';

-- 26. workflow_permission_matrix — 工作流权限矩阵表
CREATE TABLE IF NOT EXISTS workflow_permission_matrix (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    workflow_id VARCHAR(26) NOT NULL COMMENT '工作流ID',
    node_type VARCHAR(50) NOT NULL COMMENT 'REQUIREMENT/DEVELOPMENT/TESTING/DEPLOYMENT',
    required_role VARCHAR(50) NOT NULL COMMENT 'PROJECT_MANAGER/DEVELOPER/TESTER/ARCHITECT',
    permission_level VARCHAR(20) NOT NULL COMMENT 'EXECUTE/REVIEW/APPROVE',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_workflow_node_role (workflow_id, node_type, required_role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流权限矩阵表';

-- 27. workflow_audit_log — 工作流审计日志表
CREATE TABLE IF NOT EXISTS workflow_audit_log (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    workflow_id VARCHAR(26) NOT NULL COMMENT '工作流ID',
    instance_id VARCHAR(26) DEFAULT NULL COMMENT '工作流实例ID',
    node_id VARCHAR(50) DEFAULT NULL COMMENT '节点ID',
    operation VARCHAR(50) NOT NULL COMMENT 'START/COMPLETE/REVIEW/REJECT/CANCEL/TIMEOUT',
    operator_id VARCHAR(26) NOT NULL COMMENT '操作人',
    operator_name VARCHAR(50) DEFAULT NULL COMMENT '操作人名称',
    before_state VARCHAR(20) DEFAULT NULL COMMENT '操作前状态',
    after_state VARCHAR(20) DEFAULT NULL COMMENT '操作后状态',
    ip_address VARCHAR(45) DEFAULT NULL COMMENT '客户端IP',
    request_body TEXT DEFAULT NULL COMMENT '请求内容',
    response_code INT DEFAULT NULL COMMENT '响应码',
    response_time BIGINT DEFAULT NULL COMMENT '响应时间',
    created_at BIGINT NOT NULL COMMENT '审计时间',
    PRIMARY KEY (id),
    KEY idx_audit_workflow_id (workflow_id),
    KEY idx_audit_instance_id (instance_id),
    KEY idx_audit_operator_id (operator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流审计日志表';

-- ============================================================================
-- 4.6 审计与安全域 (Audit & Security) — 2 张表
-- ============================================================================

-- 28. audit_log — 审计日志表
CREATE TABLE IF NOT EXISTS audit_log (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    user_id VARCHAR(26) DEFAULT NULL COMMENT '操作用户ID',
    username VARCHAR(64) DEFAULT NULL COMMENT '用户名 (冗余)',
    action_type VARCHAR(64) NOT NULL COMMENT 'LOGIN/CREATE/UPDATE/DELETE/EXECUTE',
    resource_type VARCHAR(64) NOT NULL COMMENT 'USER/PROJECT/SESSION/AGENT/WORKFLOW',
    resource_id VARCHAR(26) DEFAULT NULL COMMENT '资源ID',
    detail JSON DEFAULT NULL COMMENT '操作详情',
    ip_address VARCHAR(45) DEFAULT NULL COMMENT '客户端IP',
    user_agent VARCHAR(512) DEFAULT NULL COMMENT '客户端UA',
    result TINYINT NOT NULL COMMENT '1=成功 0=失败',
    error_message TEXT DEFAULT NULL COMMENT '失败原因',
    duration_ms INT DEFAULT NULL COMMENT '操作耗时',
    created_at BIGINT NOT NULL COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_audit_log_user (user_id, created_at),
    KEY idx_audit_log_action (action_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志表';

-- 29. api_key — API 密钥表
CREATE TABLE IF NOT EXISTS api_key (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    user_id VARCHAR(26) NOT NULL COMMENT '所属用户ID',
    key_name VARCHAR(64) NOT NULL COMMENT '密钥名称',
    key_hash VARCHAR(256) NOT NULL COMMENT '密钥哈希',
    key_prefix VARCHAR(8) NOT NULL COMMENT '密钥前缀',
    permissions JSON DEFAULT NULL COMMENT '权限范围',
    expires_at BIGINT DEFAULT NULL COMMENT '过期时间',
    last_used_at BIGINT DEFAULT NULL COMMENT '最后使用时间',
    status TINYINT DEFAULT 1 COMMENT '1=启用 2=禁用',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_api_key_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='API 密钥表';

-- ============================================================================
-- 4.7 沙箱配置域 (Sandbox) — 2 张表
-- ============================================================================

-- 30. sandbox_config — 沙箱配置表
CREATE TABLE IF NOT EXISTS sandbox_config (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    runtime VARCHAR(32) NOT NULL COMMENT 'docker/gvisor/firecracker',
    image VARCHAR(256) DEFAULT NULL COMMENT '镜像地址',
    cpu_limit INT DEFAULT 1 COMMENT 'CPU 核心数',
    memory_limit_mb INT DEFAULT 512 COMMENT '内存限制 (MB)',
    disk_limit_mb INT DEFAULT 1024 COMMENT '磁盘限制 (MB)',
    network_enabled TINYINT DEFAULT 0 COMMENT '是否启用网络',
    seccomp_profile TEXT DEFAULT NULL COMMENT 'Seccomp 策略',
    apparmor_profile TEXT DEFAULT NULL COMMENT 'AppArmor 策略',
    read_only_fs TINYINT DEFAULT 1 COMMENT '文件系统只读',
    allowed_mounts JSON DEFAULT NULL COMMENT '允许挂载路径',
    env_vars JSON DEFAULT NULL COMMENT '环境变量',
    timeout_seconds INT DEFAULT 300 COMMENT '超时时间',
    status TINYINT DEFAULT 1 COMMENT '1=启用 2=禁用',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='沙箱配置表';

-- 31. sandbox_audit_log — 沙箱审计日志表
CREATE TABLE IF NOT EXISTS sandbox_audit_log (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    sandbox_id VARCHAR(26) NOT NULL COMMENT '沙箱ID',
    user_id VARCHAR(26) NOT NULL COMMENT '操作用户ID',
    action VARCHAR(64) NOT NULL COMMENT 'COMMAND_EXECUTED / FILE_ACCESS',
    resource VARCHAR(64) NOT NULL COMMENT 'SANDBOX',
    details JSON DEFAULT NULL COMMENT '操作详情 (命令内容/文件路径等)',
    ip_address VARCHAR(45) DEFAULT NULL COMMENT '客户端IP',
    result VARCHAR(16) NOT NULL COMMENT 'SUCCESS / FAIL (操作结果)',
    log_hash VARCHAR(64) DEFAULT NULL COMMENT 'SM3 哈希链 (防篡改校验)',
    timestamp BIGINT NOT NULL COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_audit_sandbox (sandbox_id),
    KEY idx_audit_user (user_id),
    KEY idx_audit_time (timestamp DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='沙箱审计日志表';

-- ============================================================================
-- 数据迁移工具表 — 1 张表
-- ============================================================================

-- 32. id_migration_map — ID 迁移映射表
CREATE TABLE IF NOT EXISTS id_migration_map (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    source_type VARCHAR(64) NOT NULL COMMENT '源类型 (session/project/user)',
    source_id VARCHAR(256) NOT NULL COMMENT '源 ID (OpenCode 原始 ID)',
    target_id VARCHAR(26) NOT NULL COMMENT '目标 ID (格物 ULID)',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_migration_source (source_type, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ID 迁移映射表';

-- ============================================================================
-- 初始化数据
-- ============================================================================

-- 插入默认角色
INSERT INTO role (id, role_name, role_code, description, is_system, sort_order, created_at, updated_at, created_by) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5FA0', '系统管理员', 'ADMIN', '系统管理员，拥有所有权限', 1, 1, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FA1', '架构师', 'ARCHITECT', '架构师，负责架构设计和技术评审', 1, 2, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FA2', '产品经理', 'PRODUCT_MANAGER', '产品经理，负责需求管理', 1, 3, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FA3', '后端开发', 'BACKEND_DEV', '后端开发工程师', 1, 4, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FA4', '前端开发', 'FRONTEND_DEV', '前端开发工程师', 1, 5, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FA5', '测试工程师', 'TESTER', '测试工程师', 1, 6, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FA6', '运维工程师', 'OPS_ENGINEER', '运维工程师', 1, 7, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FA7', '安全工程师', 'SECURITY_ENGINEER', '安全工程师', 1, 8, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FA8', '普通用户', 'USER', '普通用户', 1, 9, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system');

-- 插入默认权限
INSERT INTO permission (id, permission_code, permission_name, resource_type, action, description, created_at, updated_at, created_by) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5FB0', 'user:manage', '用户管理', 'USER', 'EXECUTE', '管理用户账户', UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FB1', 'project:create', '创建项目', 'PROJECT', 'CREATE', '创建新项目', UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FB2', 'project:manage', '管理项目', 'PROJECT', 'EXECUTE', '管理项目设置', UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FB3', 'session:create', '创建会话', 'SESSION', 'CREATE', '创建新会话', UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FB4', 'agent:manage', '管理Agent', 'AGENT', 'EXECUTE', '管理Agent配置', UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FB5', 'workflow:manage', '管理工作流', 'WORKFLOW', 'EXECUTE', '管理工作流定义', UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FB6', 'sandbox:manage', '管理沙箱', 'SANDBOX', 'EXECUTE', '管理沙箱配置', UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FB7', 'audit:view', '查看审计日志', 'AUDIT', 'READ', '查看审计日志', UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system');

-- ============================================================================
-- 验证
-- ============================================================================

-- 验证表数量
SELECT COUNT(*) AS table_count FROM information_schema.tables WHERE table_schema = 'gewu_dev';
-- 预期: 32

-- 验证索引数量
SELECT COUNT(*) AS index_count FROM information_schema.statistics WHERE table_schema = 'gewu_dev';
-- 预期: >= 21

-- 验证 JSON 字段
SELECT table_name, column_name, data_type
FROM information_schema.columns
WHERE table_schema = 'gewu_dev' AND data_type = 'json'
ORDER BY table_name;

-- 验证 ULID 主键
SELECT table_name, column_name, column_type
FROM information_schema.columns
WHERE table_schema = 'gewu_dev' AND column_key = 'PRI'
ORDER BY table_name;
