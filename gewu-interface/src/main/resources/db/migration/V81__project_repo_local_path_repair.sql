-- 项目 repo_local_path 数据修复：createProject 在 MP insert 赋 ULID 前拼接路径，
-- 历史数据全部落为 projects/null/repo（clone/项目文件/需求分支路径塌缩到同一目录）。
-- 修复：以真实 id 重写（E2E 冒烟捕获，伴随 createProject 显式 setId 修复）。
UPDATE project
SET repo_local_path = CONCAT('projects/', id, '/repo')
WHERE deleted = 0
  AND repo_local_path LIKE 'projects/null/%';
