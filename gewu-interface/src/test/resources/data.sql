MERGE INTO role (id, role_name, role_code, description, is_system, sort_order, created_at, updated_at, created_by) KEY(id) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5FA0', '系统管理员', 'ADMIN', '系统管理员', 1, 1, 0, 0, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FA8', '普通用户', 'USER', '普通用户', 1, 9, 0, 0, 'system');

MERGE INTO permission (id, permission_code, permission_name, resource_type, action, description, created_at, updated_at, created_by) KEY(id) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5FB1', 'project:create', '创建项目', 'PROJECT', 'CREATE', '创建新项目', 0, 0, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FB3', 'session:create', '创建会话', 'SESSION', 'CREATE', '创建新会话', 0, 0, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FB5', 'workflow:manage', '管理工作流', 'WORKFLOW', 'EXECUTE', '管理工作流定义', 0, 0, 'system');