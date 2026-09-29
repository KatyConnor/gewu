-- ============================================================
-- V72: VLF_WORKFLOW_PERMISSION_MATRIX 节点类型办理矩阵表
--   （权限体系接线：未指派行按节点类型的角色兜底）
-- 与 veloflow-engine db/upgrade/V73 同基线
-- ============================================================

CREATE TABLE IF NOT EXISTS VLF_WORKFLOW_PERMISSION_MATRIX (
    id VARCHAR(26) NOT NULL,
    workflow_id VARCHAR(26) NOT NULL,
    node_type VARCHAR(32) NOT NULL COMMENT '节点类型（如 task/approval）',
    required_role VARCHAR(50) NOT NULL COMMENT '办理所需角色',
    permission_level VARCHAR(20) DEFAULT 'APPROVE' COMMENT 'APPROVE/EXECUTE 生效，VIEW 预留',
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_vlf_pm_wf (workflow_id, node_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Veloflow 节点类型办理矩阵';
