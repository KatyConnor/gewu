-- 应用系统管理：应用系统 + 子模块服务 两表 + 权限/菜单种子
-- 设计依据：企业应用系统台账（CMDB-lite）必备元素，对齐本平台既有实体惯例
-- （organization 树/ULID 主键/BaseEntity 审计五件/system_code 唯一性校验）

-- ============================================================================
-- 1. 应用系统表
-- ============================================================================
CREATE TABLE IF NOT EXISTS app_system (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    system_code VARCHAR(64) NOT NULL COMMENT '系统编码（全局唯一，技术标识）',
    system_name VARCHAR(128) NOT NULL COMMENT '系统名称（全称）',
    short_name VARCHAR(64) DEFAULT NULL COMMENT '系统简称（展示用）',
    system_type VARCHAR(32) NOT NULL COMMENT '系统分类：BUSINESS业务/PLATFORM平台/MIDDLEWARE中间件/DATA数据/TOOL工具支撑',
    org_id VARCHAR(26) DEFAULT NULL COMMENT '所属机构ID（关联 organization）',
    owner_name VARCHAR(64) NOT NULL COMMENT '技术负责人',
    owner_contact VARCHAR(128) DEFAULT NULL COMMENT '负责人联系方式（电话/邮箱/工号）',
    repo_url VARCHAR(256) DEFAULT NULL COMMENT '代码仓库地址',
    criticality TINYINT NOT NULL DEFAULT 3 COMMENT '重要等级：1核心 2重要 3一般（运维分级依据）',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '运行状态：1启用 2停用',
    description VARCHAR(512) DEFAULT NULL COMMENT '系统描述',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序号',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_system_code (system_code),
    KEY idx_app_system_org (org_id),
    KEY idx_app_system_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='应用系统表';

-- ============================================================================
-- 2. 子模块服务表（应用系统下的服务/模块登记）
-- ============================================================================
CREATE TABLE IF NOT EXISTS app_service (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    system_id VARCHAR(26) NOT NULL COMMENT '所属应用系统ID',
    service_code VARCHAR(64) NOT NULL COMMENT '服务编码（系统内唯一）',
    service_name VARCHAR(128) NOT NULL COMMENT '服务名称',
    service_type VARCHAR(32) NOT NULL COMMENT '服务类型：BACKEND后端/FRONTEND前端/GATEWAY网关/DATABASE数据库/MIDDLEWARE中间件/SCHEDULE定时任务/MQ消息队列',
    base_url VARCHAR(256) DEFAULT NULL COMMENT '服务地址（如 http://host:port）',
    health_path VARCHAR(128) DEFAULT NULL COMMENT '健康检查路径（仅登记，管理端不做主动探测以免引入 SSRF 面）',
    version VARCHAR(32) DEFAULT NULL COMMENT '当前版本',
    owner_name VARCHAR(64) NOT NULL COMMENT '服务负责人',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '运行状态：1启用 2停用',
    description VARCHAR(512) DEFAULT NULL COMMENT '服务描述',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序号',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_service_code (system_id, service_code),
    KEY idx_app_service_system (system_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='应用系统子模块服务表';

-- ============================================================================
-- 3. 权限种子（ADMIN 角色超权直通全量权限，无需 role_permissions）
-- ============================================================================
INSERT INTO permission (id, permission_code, permission_name, resource_type, action, description, created_at, updated_at, created_by) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5FH0', 'appsystem:manage', '应用系统管理', 'SYSTEM', 'EXECUTE', '管理应用系统与子模块服务', UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system');

-- ============================================================================
-- 4. 菜单种子：应用系统（系统管理组，需 appsystem:manage 权限）
-- ============================================================================
INSERT INTO menu (id, parent_id, menu_name, menu_type, path, icon, sort_order, permission_code, visible, created_at, updated_at, created_by) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5FH1', '01ARZ3NDEKTSV4RRFFQ69G5FG3', '应用系统', 2, 'appsystem-manage', 'Boxes', 7, 'appsystem:manage', 1, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system');

-- ADMIN 角色补充应用系统菜单
INSERT INTO role_menu (id, role_id, menu_id, created_at, created_by) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5FHR', '01ARZ3NDEKTSV4RRFFQ69G5FA0', '01ARZ3NDEKTSV4RRFFQ69G5FH1', UNIX_TIMESTAMP(NOW()) * 1000, 'system');
