-- 技能(skill)表
CREATE TABLE IF NOT EXISTS skill (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    skill_name VARCHAR(128) NOT NULL COMMENT '技能名称',
    description VARCHAR(1024) DEFAULT NULL COMMENT '技能描述',
    category VARCHAR(64) DEFAULT NULL COMMENT '分类',
    content TEXT DEFAULT NULL COMMENT '技能内容/定义',
    emoji VARCHAR(16) DEFAULT '⚡' COMMENT '图标',
    tags JSON DEFAULT NULL COMMENT '标签数组',
    install_count INT DEFAULT 0 COMMENT '安装次数',
    status TINYINT DEFAULT 1 COMMENT '1=启用 0=禁用',
    version INT DEFAULT 0 COMMENT '版本号',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    KEY idx_skill_created_by (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='技能表';
