MERGE INTO role (id, role_name, role_code, description, is_system, sort_order, data_scope, created_at, updated_at, created_by) KEY(id) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5FA0', '系统管理员', 'ADMIN', '系统管理员', 1, 1, 1, 0, 0, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FA8', '普通用户', 'USER', '普通用户', 1, 9, 4, 0, 0, 'system');

MERGE INTO permission (id, permission_code, permission_name, resource_type, action, description, created_at, updated_at, created_by) KEY(id) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5FB1', 'project:create', '创建项目', 'PROJECT', 'CREATE', '创建新项目', 0, 0, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FB3', 'session:create', '创建会话', 'SESSION', 'CREATE', '创建新会话', 0, 0, 'system'),
('01ARZ3NDEKTSV4RRFFQ69G5FB5', 'workflow:manage', '管理工作流', 'WORKFLOW', 'EXECUTE', '管理工作流定义', 0, 0, 'system');

-- 预置管理员账号 (与 V18 迁移一致)
MERGE INTO user_account (id, username, email, password_hash, password_salt, display_name, status, org_id, login_fail_count, deleted, version, created_at, updated_at, created_by) KEY(id) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5FU0', '001_gewu_admin', 'admin@gewu.com', '10000$efa4e29c30d0703dc10e3381734dd993a0c05775421b1ab36c6f573f0fcb6c53$4eed6a327c38c9cd9c55ed43b2dbe2b61dcfa3b1d6a5c43bc236e41411013bcc', 'efa4e29c30d0703dc10e3381734dd993a0c05775421b1ab36c6f573f0fcb6c53', '系统管理员', 1, '01ARZ3NDEKTSV4RRFFQ69G5FK0', 0, 0, 0, 0, 0, 'system');

MERGE INTO user_role (id, user_id, role_id, source, created_at, created_by) KEY(id) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5RU0', '01ARZ3NDEKTSV4RRFFQ69G5FU0', '01ARZ3NDEKTSV4RRFFQ69G5FA0', 'system', 0, 'system');