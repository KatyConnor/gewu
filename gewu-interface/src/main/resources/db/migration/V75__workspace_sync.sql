-- V75: 工作空间双向同步（设计文档 42 项三，文档类文件；项目代码目录排除，代码走 Git 管理）
-- workspace_sync_state：同步待办与结果台账（事件驱动入队 + 周期对账兜底共用）
--
-- 索引说明（修正版）：file_path 为 VARCHAR(1024)，utf8mb4 下直接参与唯一键会超过
-- MySQL 3072 字节上限（Error 1071，首版启动失败根因）。改为 file_path_hash
-- （SHA-256 十六进制，路径含 workspace_id 归一化后取摘要）参与唯一键。

CREATE TABLE IF NOT EXISTS workspace_sync_state (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    workspace_id VARCHAR(26) NOT NULL COMMENT '工作空间ID',
    file_path VARCHAR(1024) NOT NULL COMMENT '卷内相对路径(=workspace_file.file_path)',
    file_path_hash CHAR(64) NOT NULL COMMENT 'SHA-256(workspace_id|file_path)，唯一键用',
    direction TINYINT NOT NULL COMMENT '1=V2M(卷→MinIO) 2=M2V(MinIO→卷) 3=DEL_V(卷删→MinIO墓碑) 4=DEL_M(MinIO删→卷删除)',
    source_mtime BIGINT DEFAULT NULL COMMENT '入队时卷侧 mtime(秒)，冲突判定基准',
    source_size BIGINT DEFAULT NULL COMMENT '入队时源侧大小(字节)',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=待办 1=成功 2=冲突(已生成副本) 3=失败',
    fail_reason VARCHAR(500) DEFAULT NULL COMMENT '失败原因',
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ws_sync_hash (workspace_id, file_path_hash),
    KEY idx_ws_sync_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工作空间双向同步状态'
