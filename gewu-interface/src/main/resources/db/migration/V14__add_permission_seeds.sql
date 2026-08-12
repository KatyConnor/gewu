-- 补充管理类权限码（用于 @PreAuthorize hasAuthority 权限码化）
INSERT INTO permission (id, permission_code, permission_name, resource_type, action, description, created_at, updated_at, created_by) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5FE0', 'role:manage', '角色权限管理', 'ROLE', 'EXECUTE', '管理角色与权限', UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FE1', 'audit:approve', '审批操作', 'AUDIT', 'EXECUTE', '审批待审核项', UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FE2', 'org:manage', '机构管理', 'ORG', 'EXECUTE', '管理组织架构', UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FE3', 'menu:manage', '菜单管理', 'MENU', 'EXECUTE', '管理菜单配置', UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system');
-- 注：ADMIN 角色由 AuthService.getPermissionsByUserId 超权返回全部权限码（含以上4个），
-- 注册为 Spring authority 后 hasAuthority('role:manage') 等对 ADMIN 生效。
