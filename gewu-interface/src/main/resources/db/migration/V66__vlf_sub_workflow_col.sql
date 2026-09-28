-- ============================================================
-- V66: sub-workflow 子工作流节点（53 号 §3.7）
--   node_instance.child_instance_id：挂起时登记子实例 ID，
--   子实例终态经运行时监听器唤醒父等待行（触发类型 SUB_WORKFLOW 语义）
-- 与 veloflow-engine db/upgrade/V67 同基线
-- ============================================================

ALTER TABLE VLF_WORKFLOW_NODE_INSTANCE
    ADD COLUMN child_instance_id VARCHAR(26) DEFAULT NULL COMMENT '子工作流实例 ID' AFTER message_key;

ALTER TABLE VLF_WORKFLOW_NODE_INSTANCE
    ADD INDEX idx_vlf_ni_child (child_instance_id, status);
