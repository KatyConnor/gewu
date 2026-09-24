-- V44: 配额套餐菜单下发（用户实报：动态菜单模式下侧栏无"配额套餐"入口）
-- 菜单主路径 = menu 表按角色过滤（V15 机制），仅加前端静态 fallback 会被动态菜单遮住。
-- ID 沿用 V15 的 ULID 派生规则（base + 单字符），FGS / RSJ 为未占用槽位。

-- 菜单项：系统管理组（parent=…G5FG3）下的"配额套餐"（path 对应前端 PageType: quota-manage）
INSERT INTO menu (id, parent_id, menu_name, menu_type, path, icon, sort_order, permission_code, visible, created_at, updated_at, created_by) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5FGS', '01ARZ3NDEKTSV4RRFFQ69G5FG3', '配额套餐', 2, 'quota-manage', 'Wallet', 2, NULL, 1, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system');

-- 授权 ADMIN 角色（…G5FA0）可见
INSERT INTO role_menu (id, role_id, menu_id, created_at, created_by) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5RSJ', '01ARZ3NDEKTSV4RRFFQ69G5FA0', '01ARZ3NDEKTSV4RRFFQ69G5FGS', UNIX_TIMESTAMP(NOW()) * 1000, 'system');
