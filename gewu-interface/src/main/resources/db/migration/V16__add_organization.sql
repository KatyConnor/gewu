-- 阶段 C：机构/组织 - organization 表 + user_account.org_id + 种子数据

-- 组织表（树形结构）
CREATE TABLE IF NOT EXISTS organization (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    parent_id VARCHAR(26) DEFAULT NULL COMMENT '父机构ID（NULL为顶级）',
    org_name VARCHAR(128) NOT NULL COMMENT '机构名称',
    org_code VARCHAR(64) NOT NULL COMMENT '机构编码',
    sort_order INT DEFAULT 0 COMMENT '排序号',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    KEY idx_org_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组织机构表';

-- user_account 增加所属机构字段
ALTER TABLE user_account ADD COLUMN org_id VARCHAR(26) DEFAULT NULL COMMENT '所属机构ID';

-- ============================================================================
-- 组织种子
-- ============================================================================

INSERT INTO organization (id, parent_id, org_name, org_code, sort_order, created_at, updated_at, created_by) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5FK0', NULL, '总部', 'HQ', 1, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FK1', '01ARZ3NDEKTSV4RRFFQ69G5FK0', '研发中心', 'RND', 1, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FK2', '01ARZ3NDEKTSV4RRFFQ69G5FK0', '产品中心', 'PRODUCT', 2, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FK3', '01ARZ3NDEKTSV4RRFFQ69G5FK0', '运维中心', 'OPS', 3, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system');

-- ============================================================================
-- 补充菜单种子：机构管理（系统管理组，需 org:manage 权限）
-- ============================================================================

INSERT INTO menu (id, parent_id, menu_name, menu_type, path, icon, sort_order, permission_code, visible, created_at, updated_at, created_by) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5FGR', '01ARZ3NDEKTSV4RRFFQ69G5FG3', '机构管理', 2, 'org-manage', 'Building2', 6, 'org:manage', 1, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system');

-- ADMIN 角色补充机构管理菜单
INSERT INTO role_menu (id, role_id, menu_id, created_at, created_by) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5RHR', '01ARZ3NDEKTSV4RRFFQ69G5FA0', '01ARZ3NDEKTSV4RRFFQ69G5FGR', UNIX_TIMESTAMP(NOW()) * 1000, 'system');
