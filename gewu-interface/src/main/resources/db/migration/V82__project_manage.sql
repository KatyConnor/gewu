-- ============================================================================
-- V82: 项目管理迁移管理端（用户侧收敛为只读"我的项目"）
--   - 权限复用 V1 既有种子 project:manage（01ARZ3NDEKTSV4RRFFQ69G5FB2，原"管理项目"），
--     不再重复 INSERT（重复插入会撞 permission.uk_permission_code，见 2026-10-24 启动失败）
--   - 菜单：系统管理组下"项目管理"（path=project-manage，对应 admin-web PageType）
--   - 分发模型：项目归属过滤沿用 project_member（user_id）行，
--     技术负责人落 role_code=TECH_LEAD，团队成员落 role_code=MEMBER
-- ============================================================================

-- 1. 菜单种子：项目管理（系统管理组，需既有 project:manage 权限；ADMIN 角色超权直通全量权限，无需 role_permissions）
-- ============================================================================
INSERT INTO menu (id, parent_id, menu_name, menu_type, path, icon, sort_order, permission_code, visible, created_at, updated_at, created_by) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5FH3', '01ARZ3NDEKTSV4RRFFQ69G5FG3', '项目管理', 2, 'project-manage', 'FolderKanban', 8, 'project:manage', 1, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system');
UPDATE menu set menu_name = '我的项目' wherer id = '01ARZ3NDEKTSV4RRFFQ69G5FG6';

-- 2. ADMIN 角色补充项目管理菜单
-- ============================================================================
INSERT INTO role_menu (id, role_id, menu_id, created_at, created_by) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5FHS', '01ARZ3NDEKTSV4RRFFQ69G5FA0', '01ARZ3NDEKTSV4RRFFQ69G5FH3', UNIX_TIMESTAMP(NOW()) * 1000, 'system');
