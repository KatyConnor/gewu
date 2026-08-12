-- 三级工作空间: 项目仓库 + 需求文件空间 + 需求分支关联

-- project 表增加 Git 仓库字段（替代未使用的 worktree/sandboxes）
ALTER TABLE project ADD COLUMN repo_url VARCHAR(512) DEFAULT NULL COMMENT 'Git仓库地址';
ALTER TABLE project ADD COLUMN repo_branch VARCHAR(128) DEFAULT 'main' COMMENT '默认分支';
ALTER TABLE project ADD COLUMN clone_status VARCHAR(32) DEFAULT NULL COMMENT 'clone状态: pending/cloning/ready/failed';
ALTER TABLE project ADD COLUMN repo_local_path VARCHAR(256) DEFAULT NULL COMMENT '容器内路径(如 projects/{projectId}/repo)';
ALTER TABLE project ADD COLUMN head_commit VARCHAR(64) DEFAULT NULL COMMENT '当前HEAD commit SHA';

-- requirement 表增加 Git 分支字段
ALTER TABLE requirement ADD COLUMN git_branch VARCHAR(128) DEFAULT NULL COMMENT '需求开发分支(如 feature/REQ-001)';

-- 需求文件空间表（docs/tests/reports 三类，MinIO 元数据）
CREATE TABLE IF NOT EXISTS requirement_file (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    requirement_id VARCHAR(26) NOT NULL COMMENT '需求ID',
    category VARCHAR(32) NOT NULL COMMENT '文件分类: docs/tests/reports',
    file_name VARCHAR(255) NOT NULL COMMENT '文件名',
    object_key VARCHAR(512) NOT NULL COMMENT 'MinIO对象Key',
    mime_type VARCHAR(128) DEFAULT NULL COMMENT 'MIME类型',
    file_size BIGINT DEFAULT 0 COMMENT '文件大小(字节)',
    version INT NOT NULL DEFAULT 1 COMMENT '版本号',
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_reqfile_requirement (requirement_id),
    KEY idx_reqfile_category (requirement_id, category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需求文件空间';
