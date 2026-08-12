-- 用户工作空间表
CREATE TABLE IF NOT EXISTS workspace (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    user_id VARCHAR(26) NOT NULL COMMENT '所属用户ID',
    workspace_name VARCHAR(128) NOT NULL DEFAULT '我的工作空间' COMMENT '工作空间名称',
    storage_path VARCHAR(256) NOT NULL COMMENT 'MinIO 存储路径前缀',
    quota_bytes BIGINT NOT NULL DEFAULT 1073741824 COMMENT '配额上限(字节)',
    used_bytes BIGINT NOT NULL DEFAULT 0 COMMENT '已用空间(字节)',
    file_count INT NOT NULL DEFAULT 0 COMMENT '文件总数',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=正常 2=冻结',
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_workspace_user (user_id),
    KEY idx_workspace_path (storage_path)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户工作空间';

-- 工作空间文件元数据表
CREATE TABLE IF NOT EXISTS workspace_file (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    workspace_id VARCHAR(26) NOT NULL COMMENT '工作空间ID',
    parent_id VARCHAR(26) DEFAULT NULL COMMENT '父目录ID(NULL=根目录)',
    file_name VARCHAR(255) NOT NULL COMMENT '文件/目录名',
    file_type TINYINT NOT NULL COMMENT '1=目录 2=文件',
    file_path VARCHAR(1024) NOT NULL COMMENT '完整相对路径',
    object_key VARCHAR(512) DEFAULT NULL COMMENT 'MinIO对象Key(文件类型才有)',
    mime_type VARCHAR(128) DEFAULT NULL COMMENT 'MIME类型',
    file_size BIGINT DEFAULT 0 COMMENT '文件大小(字节)',
    checksum VARCHAR(64) DEFAULT NULL COMMENT 'SHA-256校验和',
    version INT NOT NULL DEFAULT 1 COMMENT '版本号',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=正常 2=回收站',
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_file_workspace (workspace_id),
    KEY idx_file_parent (parent_id),
    KEY idx_file_path (workspace_id, file_path(768))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作空间文件元数据';

-- sandbox 表增加 workspace_id 列
ALTER TABLE sandbox ADD COLUMN workspace_id VARCHAR(26) DEFAULT NULL COMMENT '关联工作空间ID';
