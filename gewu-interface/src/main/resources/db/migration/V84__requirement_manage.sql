-- ============================================================================
-- V84: 需求管理迁移管理端（用户侧收敛为只读"我的需求"）
--   - 权限 requirement:manage（新建码，全仓查重无占用；V13 既有 requirement:view/create/edit/delete 粒度码保留不动）
--   - 菜单：系统管理组下"需求管理"（path=requirement-manage，对应 admin-web PageType，排序接 V82 项目管理之后）
--   - 分派模型：requirement.assignee_id 即分派对象（"我的需求"= assignee=当前用户）；
--     分派校验 = assignee 必须是 requirement.project_id 绑定项目的 project_member 成员（OWNER/TECH_LEAD/MEMBER 任一）
-- ============================================================================

-- 1. 权限种子（ADMIN 角色超权直通全量权限，无需 role_permissions）
-- ============================================================================
INSERT INTO permission (id, permission_code, permission_name, resource_type, action, description, created_at, updated_at, created_by) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5FH4', 'requirement:manage', '需求管理', 'REQUIREMENT', 'EXECUTE', '管理端需求创建/编辑/删除/状态流转与分派', UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system');

-- 2. 菜单种子：需求管理（系统管理组，需 requirement:manage 权限）
-- ============================================================================
INSERT INTO menu (id, parent_id, menu_name, menu_type, path, icon, sort_order, permission_code, visible, created_at, updated_at, created_by) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5FH5', '01ARZ3NDEKTSV4RRFFQ69G5FG3', '需求管理', 2, 'requirement-manage', 'ClipboardList', 9, 'requirement:manage', 1, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system');

-- 3. ADMIN 角色补充需求管理菜单
-- ============================================================================
INSERT INTO role_menu (id, role_id, menu_id, created_at, created_by) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5FHT', '01ARZ3NDEKTSV4RRFFQ69G5FA0', '01ARZ3NDEKTSV4RRFFQ69G5FH5', UNIX_TIMESTAMP(NOW()) * 1000, 'system');
