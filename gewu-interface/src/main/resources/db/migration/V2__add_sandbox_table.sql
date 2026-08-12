-- V2: 添加沙箱运行实例表 (sandbox)
-- 修复: V1 遗漏了 sandbox 表，导致运行时 NoResourceFoundException

CREATE TABLE IF NOT EXISTS sandbox (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    config_id VARCHAR(26) DEFAULT NULL COMMENT '关联配置ID',
    container_id VARCHAR(128) DEFAULT NULL COMMENT 'Docker 容器ID',
    sandbox_name VARCHAR(128) DEFAULT NULL COMMENT '沙箱名称',
    status VARCHAR(32) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/RUNNING/STOPPED/DESTROYED/ERROR',
    image VARCHAR(256) DEFAULT NULL COMMENT '镜像地址',
    cpu_limit INT DEFAULT 1 COMMENT 'CPU 核心数',
    memory_limit_mb INT DEFAULT 512 COMMENT '内存限制 (MB)',
    disk_limit_mb INT DEFAULT 1024 COMMENT '磁盘限制 (MB)',
    network_enabled TINYINT DEFAULT 0 COMMENT '是否启用网络',
    timeout_seconds INT DEFAULT 300 COMMENT '超时时间',
    runtime VARCHAR(32) DEFAULT 'docker' COMMENT 'docker/gvisor/firecracker',
    started_at BIGINT DEFAULT NULL COMMENT '启动时间',
    stopped_at BIGINT DEFAULT NULL COMMENT '停止时间',
    ip VARCHAR(45) DEFAULT NULL COMMENT '容器IP地址',
    ports VARCHAR(256) DEFAULT NULL COMMENT '端口映射',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建用户ID',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人ID',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除 0=正常 1=删除',
    PRIMARY KEY (id),
    KEY idx_sandbox_status (status),
    KEY idx_sandbox_created_by (created_by),
    KEY idx_sandbox_container (container_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='沙箱运行实例表';
