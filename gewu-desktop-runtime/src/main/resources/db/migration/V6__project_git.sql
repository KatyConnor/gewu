-- 桌面运行时本地存储 V6：开发空间（对齐 web DevWorkspacePage）。
-- local_project 增加 Git 仓库来源列——开发空间克隆的仓库落地为本地项目
--（绑定目录=克隆目标目录），会话/文件工具/终端沿用既有项目工作区机制。
ALTER TABLE local_project ADD COLUMN IF NOT EXISTS repo_url VARCHAR(1024);
ALTER TABLE local_project ADD COLUMN IF NOT EXISTS repo_branch VARCHAR(128);
