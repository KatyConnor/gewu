-- 桌面运行时本地存储 V2：本地项目实体（D1-25 项目分组落地）。
-- 项目绑定本地目录（必选——文件工具/终端/Git 以会话 directory 为工作区，目录即工作区语义）；
-- 会话经 project_id 关联项目，无项目会话留在默认空间（project_id IS NULL，既有行为不变）。
CREATE TABLE IF NOT EXISTS local_project (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    directory VARCHAR(1024) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_project_dir ON local_project(directory);
