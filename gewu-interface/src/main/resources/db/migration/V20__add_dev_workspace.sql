-- 开发工作空间扩展

-- workspace 表增加开发模式字段
ALTER TABLE workspace ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'storage'
    COMMENT '存储模式: storage=MinIO对象存储, dev=Docker卷开发环境';
ALTER TABLE workspace ADD COLUMN dev_sandbox_id VARCHAR(26) DEFAULT NULL
    COMMENT '开发沙箱ID (mode=dev时关联)';

-- 工作空间 Git 项目表
CREATE TABLE IF NOT EXISTS workspace_project (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    workspace_id VARCHAR(26) NOT NULL COMMENT '工作空间ID',
    project_name VARCHAR(128) NOT NULL COMMENT '项目名称',
    repo_url VARCHAR(512) NOT NULL COMMENT 'Git仓库地址',
    repo_branch VARCHAR(128) DEFAULT 'main' COMMENT '分支',
    local_path VARCHAR(256) NOT NULL COMMENT '容器内相对路径',
    clone_status VARCHAR(32) DEFAULT 'pending' COMMENT 'pending/cloning/ready/failed',
    last_sync_at BIGINT DEFAULT NULL COMMENT '最后git pull时间',
    head_commit VARCHAR(64) DEFAULT NULL COMMENT '当前HEAD commit SHA',
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_wp_workspace (workspace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作空间Git项目';

-- Git 凭证表
CREATE TABLE IF NOT EXISTS git_credential (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    user_id VARCHAR(26) NOT NULL COMMENT '所属用户',
    cred_name VARCHAR(64) NOT NULL COMMENT '凭证名称',
    cred_type VARCHAR(16) NOT NULL COMMENT 'ssh_key / token',
    cred_value TEXT NOT NULL COMMENT 'SM4加密后的凭证内容',
    ssh_public_key TEXT DEFAULT NULL COMMENT 'SSH公钥(cred_type=ssh_key时)',
    git_host VARCHAR(256) DEFAULT NULL COMMENT 'Git服务地址',
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_git_cred_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Git凭证';
