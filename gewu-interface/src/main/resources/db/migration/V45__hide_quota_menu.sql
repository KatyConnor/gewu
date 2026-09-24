-- V45: 主应用下架"配额套餐"菜单（管理功能已迁独立后台管理端 gewu-admin-web/admin-server）
-- 保留记录并置为不可见，避免主应用动态菜单继续渲染已迁出的入口
UPDATE menu SET visible = 0, updated_at = UNIX_TIMESTAMP(NOW()) * 1000, updated_by = 'system'
WHERE path = 'quota-manage' AND deleted = 0;
