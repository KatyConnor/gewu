-- ============================================================
-- V51: 编排断点检查点持久化（EXEPLAN-ORCH-2026-09 / WFO-03）
-- 暂停生效时由引擎经 CheckpointStore 双写（内存 + 本表）；
-- 进程重启后恢复接口从本表重建检查点断点续跑，恢复即删。
-- ============================================================

CREATE TABLE IF NOT EXISTS orchestration_checkpoint (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    execution_id VARCHAR(26) NOT NULL COMMENT '编排执行实例 ID（唯一）',
    graph_id VARCHAR(26) DEFAULT NULL COMMENT '编排图 ID',
    graph_snapshot LONGTEXT NOT NULL COMMENT '检查点图定义 JSON',
    variables LONGTEXT COMMENT '检查点变量快照 JSON',
    resume_from_node VARCHAR(64) DEFAULT NULL COMMENT '恢复起始节点 ID',
    current_node_id VARCHAR(64) DEFAULT NULL COMMENT '暂停时刻当前节点 ID',
    user_id VARCHAR(26) DEFAULT NULL COMMENT '发起用户 ID',
    session_id VARCHAR(26) DEFAULT NULL COMMENT '会话 ID',
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_orch_checkpoint_exec (execution_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='编排断点检查点（恢复成功即删）';
