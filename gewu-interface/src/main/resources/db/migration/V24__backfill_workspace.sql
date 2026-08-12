-- ============================================================
-- V24: 回填存量用户工作空间
-- 问题：AuthService.register() 历史上未创建工作空间，且无回填脚本，
--       导致所有存量用户（含管理员）缺失 workspace 记录。
-- 修复：为每个未分配工作空间的用户创建 workspace + 默认目录(src/docs/uploads)。
-- 注意：user_account/user_role/role 使用 utf8mb4_unicode_ci，
--       workspace/workspace_file 使用 utf8mb4_0900_ai_ci，
--       跨表比较需加 COLLATE utf8mb4_unicode_ci。
-- 幂等：NOT EXISTS 保护，可安全重复执行。
-- ============================================================

-- Step 1: 为缺失工作空间的用户创建 workspace 记录
INSERT INTO workspace (
    id, user_id, workspace_name, storage_path,
    quota_bytes, used_bytes, file_count, status, deleted,
    created_at, updated_at, created_by, updated_by, mode
)
SELECT
    SUBSTR(REPLACE(UUID(), '-', ''), 1, 26) AS id,
    u.id AS user_id,
    '我的工作空间' AS workspace_name,
    CONCAT('workspaces/', u.id, '/') AS storage_path,
    -- 按角色确定配额：ADMIN=20GB, 开发角色=5GB, 默认=1GB
    CASE
        WHEN EXISTS (
            SELECT 1 FROM user_role ur
            JOIN role r ON ur.role_id = r.id COLLATE utf8mb4_unicode_ci
            WHERE ur.user_id = u.id COLLATE utf8mb4_unicode_ci
              AND r.role_code = 'ADMIN'
        ) THEN 21474836480
        WHEN EXISTS (
            SELECT 1 FROM user_role ur
            JOIN role r ON ur.role_id = r.id COLLATE utf8mb4_unicode_ci
            WHERE ur.user_id = u.id COLLATE utf8mb4_unicode_ci
              AND r.role_code IN ('BACKEND_DEV', 'FRONTEND_DEV', 'TESTER')
        ) THEN 5368709120
        ELSE 1073741824
    END AS quota_bytes,
    0 AS used_bytes,
    0 AS file_count,
    1 AS status,
    0 AS deleted,
    UNIX_TIMESTAMP(NOW(3)) * 1000 AS created_at,
    UNIX_TIMESTAMP(NOW(3)) * 1000 AS updated_at,
    u.id AS created_by,
    u.id AS updated_by,
    'storage' AS mode
FROM user_account u
WHERE u.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM workspace w
      WHERE w.user_id = u.id COLLATE utf8mb4_unicode_ci
        AND w.deleted = 0
  );

-- Step 2: 为每个新创建（或既有无目录）的工作空间添加默认目录
INSERT INTO workspace_file (
    id, workspace_id, parent_id, file_name, file_type, file_path,
    file_size, version, status, deleted,
    created_at, updated_at, created_by, updated_by
)
SELECT
    SUBSTR(REPLACE(UUID(), '-', ''), 1, 26) AS id,
    w.id AS workspace_id,
    NULL AS parent_id,
    dirs.dir_name AS file_name,
    1 AS file_type,
    dirs.dir_name AS file_path,
    0 AS file_size,
    1 AS version,
    1 AS status,
    0 AS deleted,
    UNIX_TIMESTAMP(NOW(3)) * 1000 AS created_at,
    UNIX_TIMESTAMP(NOW(3)) * 1000 AS updated_at,
    w.user_id AS created_by,
    w.user_id AS updated_by
FROM workspace w
CROSS JOIN (
    SELECT 'src' AS dir_name
    UNION ALL SELECT 'docs'
    UNION ALL SELECT 'uploads'
) dirs
WHERE w.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM workspace_file wf
      WHERE wf.workspace_id = w.id
        AND wf.parent_id IS NULL
        AND wf.file_name = dirs.dir_name
        AND wf.deleted = 0
  );
