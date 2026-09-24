-- V46: 主应用下架已迁移至独立后台管理端（gewu-admin-web）的功能菜单
-- 系统管理组仅保留"审批中心"；配额套餐已于 V45 下架
-- visible=0 而非逻辑删除：保留角色授权关系，回滚只需重新置 1
UPDATE menu SET visible = 0, updated_at = UNIX_TIMESTAMP(NOW()) * 1000, updated_by = 'system'
WHERE path IN ('user-manage', 'role-manage', 'menu-manage', 'org-manage', 'skill-audit')
  AND deleted = 0;
