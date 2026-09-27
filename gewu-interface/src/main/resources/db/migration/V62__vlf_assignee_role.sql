-- ============================================================
-- V62: 节点实例审批/办理角色列（52 号 P2，与 veloflow upgrade V61 同基线）
-- ============================================================

ALTER TABLE VLF_WORKFLOW_NODE_INSTANCE
    ADD COLUMN assignee_role VARCHAR(64) DEFAULT NULL COMMENT '指派审批/办理角色' AFTER assignee_id;
