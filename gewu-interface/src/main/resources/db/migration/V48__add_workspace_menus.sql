-- V48: 工作空间组补齐缺失入口（用户实报：侧栏无"我的文件/开发空间"按钮）
-- 根因：V15 种子只配了工作空间组 6 项（主页/会话/项目管理/需求管理/原型/工作流），
--       Sidebar 静态 navGroups 中的 workspace / dev-workspace / orchestration 三项从未入库，
--       而 Sidebar 以 /v1/menus/current 返回整体覆盖静态兜底，动态模式下入口消失。
-- 方案：补 3 条菜单（父目录按 menu_name 定位，规避 V15 行内 ULID 手抄差异）；
--       sort_order 重排对齐 Sidebar navGroups 设计顺序；授权 ADMIN/USER（V15 模式，
--       ADMIN 走全量可见快路径，普通角色必须经 role_menu 才可见）。
-- ID 槽位：菜单 FGT/FGV/FGW，授权 RSK/RSM/RSN/RSP/RSQ/RST
--         （V44 已占用 FGS/RSJ；Crockford Base32 跳过 I/L/O/U，L/O 槽位不使用）。

-- 1) 新增菜单（父目录 = 「工作空间」，menu_type=2）
INSERT INTO menu (id, parent_id, menu_name, menu_type, path, icon, sort_order, permission_code, visible, created_at, updated_at, created_by)
SELECT '01ARZ3NDEKTSV4RRFFQ69G5FGT', dir.id, '我的文件', 2, 'workspace', 'FolderOpen', 4, NULL, 1,
       UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'
FROM menu dir
WHERE dir.menu_name = '工作空间' AND dir.menu_type = 1 AND dir.deleted = 0
LIMIT 1;

INSERT INTO menu (id, parent_id, menu_name, menu_type, path, icon, sort_order, permission_code, visible, created_at, updated_at, created_by)
SELECT '01ARZ3NDEKTSV4RRFFQ69G5FGV', dir.id, '开发空间', 2, 'dev-workspace', 'GitBranch', 5, NULL, 1,
       UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'
FROM menu dir
WHERE dir.menu_name = '工作空间' AND dir.menu_type = 1 AND dir.deleted = 0
LIMIT 1;

INSERT INTO menu (id, parent_id, menu_name, menu_type, path, icon, sort_order, permission_code, visible, created_at, updated_at, created_by)
SELECT '01ARZ3NDEKTSV4RRFFQ69G5FGW', dir.id, '编排引擎', 2, 'orchestration', 'Network', 9, NULL, 1,
       UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'
FROM menu dir
WHERE dir.menu_name = '工作空间' AND dir.menu_type = 1 AND dir.deleted = 0
LIMIT 1;

-- 2) 既有项重排，对齐 Sidebar 静态设计顺序
--    （主页1 会话2 项目管理3 我的文件4 开发空间5 需求管理6 原型7 工作流8 编排引擎9）
UPDATE menu SET sort_order = 6, updated_at = UNIX_TIMESTAMP(NOW()) * 1000, updated_by = 'system'
WHERE path = 'requirements' AND menu_type = 2 AND deleted = 0;

UPDATE menu SET sort_order = 7, updated_at = UNIX_TIMESTAMP(NOW()) * 1000, updated_by = 'system'
WHERE path = 'prototype' AND menu_type = 2 AND deleted = 0;

UPDATE menu SET sort_order = 8, updated_at = UNIX_TIMESTAMP(NOW()) * 1000, updated_by = 'system'
WHERE path = 'workflow' AND menu_type = 2 AND deleted = 0;

-- 3) 角色授权：ADMIN 沿 V15/V44 惯例补齐（其可见性走全量路径，授权为一致性冗余）；
--    USER 是普通角色的实际可见通道。
INSERT INTO role_menu (id, role_id, menu_id, created_at, created_by)
SELECT '01ARZ3NDEKTSV4RRFFQ69G5RSK', r.id, '01ARZ3NDEKTSV4RRFFQ69G5FGT', UNIX_TIMESTAMP(NOW()) * 1000, 'system'
FROM role r WHERE r.role_code = 'ADMIN' LIMIT 1;

INSERT INTO role_menu (id, role_id, menu_id, created_at, created_by)
SELECT '01ARZ3NDEKTSV4RRFFQ69G5RSM', r.id, '01ARZ3NDEKTSV4RRFFQ69G5FGT', UNIX_TIMESTAMP(NOW()) * 1000, 'system'
FROM role r WHERE r.role_code = 'USER' LIMIT 1;

INSERT INTO role_menu (id, role_id, menu_id, created_at, created_by)
SELECT '01ARZ3NDEKTSV4RRFFQ69G5RSN', r.id, '01ARZ3NDEKTSV4RRFFQ69G5FGV', UNIX_TIMESTAMP(NOW()) * 1000, 'system'
FROM role r WHERE r.role_code = 'ADMIN' LIMIT 1;

INSERT INTO role_menu (id, role_id, menu_id, created_at, created_by)
SELECT '01ARZ3NDEKTSV4RRFFQ69G5RSP', r.id, '01ARZ3NDEKTSV4RRFFQ69G5FGV', UNIX_TIMESTAMP(NOW()) * 1000, 'system'
FROM role r WHERE r.role_code = 'USER' LIMIT 1;

INSERT INTO role_menu (id, role_id, menu_id, created_at, created_by)
SELECT '01ARZ3NDEKTSV4RRFFQ69G5RSQ', r.id, '01ARZ3NDEKTSV4RRFFQ69G5FGW', UNIX_TIMESTAMP(NOW()) * 1000, 'system'
FROM role r WHERE r.role_code = 'ADMIN' LIMIT 1;

INSERT INTO role_menu (id, role_id, menu_id, created_at, created_by)
SELECT '01ARZ3NDEKTSV4RRFFQ69G5RST', r.id, '01ARZ3NDEKTSV4RRFFQ69G5FGW', UNIX_TIMESTAMP(NOW()) * 1000, 'system'
FROM role r WHERE r.role_code = 'USER' LIMIT 1;
