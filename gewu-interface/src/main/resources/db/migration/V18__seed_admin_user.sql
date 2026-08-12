-- 预置管理员账号: 001_gewu_admin / Gewu@2026
-- ADMIN 角色 (id=...FA0) 由 V1 种子预置，此处仅创建用户并关联角色
-- 密码哈希由 PasswordHasher.hash("Gewu@2026") 预计算 (SM3, 10000 迭代)

INSERT INTO user_account (id, username, email, password_hash, password_salt, display_name, status, org_id, login_fail_count, created_at, updated_at, created_by)
VALUES (
    '01ARZ3NDEKTSV4RRFFQ69G5FU0',
    '001_gewu_admin',
    'admin@gewu.com',
    '10000$efa4e29c30d0703dc10e3381734dd993a0c05775421b1ab36c6f573f0fcb6c53$4eed6a327c38c9cd9c55ed43b2dbe2b61dcfa3b1d6a5c43bc236e41411013bcc',
    'efa4e29c30d0703dc10e3381734dd993a0c05775421b1ab36c6f573f0fcb6c53',
    '系统管理员',
    1,
    '01ARZ3NDEKTSV4RRFFQ69G5FK0',
    0,
    UNIX_TIMESTAMP(NOW()) * 1000,
    UNIX_TIMESTAMP(NOW()) * 1000,
    'system'
);

-- 关联 ADMIN 角色
INSERT INTO user_role (id, user_id, role_id, source, created_at, created_by)
VALUES (
    '01ARZ3NDEKTSV4RRFFQ69G5RU0',
    '01ARZ3NDEKTSV4RRFFQ69G5FU0',
    '01ARZ3NDEKTSV4RRFFQ69G5FA0',
    'system',
    UNIX_TIMESTAMP(NOW()) * 1000,
    'system'
);
