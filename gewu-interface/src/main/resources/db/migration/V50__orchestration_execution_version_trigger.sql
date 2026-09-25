-- ============================================================
-- V50: 编排执行实例绑定版本与触发方式（WFO-01 / WFC-04）
-- version_id 指向 orchestration_graph_version，回放时以版本快照为准；
-- trigger_type 记录触发来源（MANUAL/API/AGENT_TOOL/SCHEDULE/WEBHOOK）。
-- 同时为节点执行记录补 retry_count（WFO-05 节点级重试）。
-- ============================================================

ALTER TABLE orchestration_execution
    ADD COLUMN version_id VARCHAR(26) DEFAULT NULL COMMENT '执行绑定的图版本 ID（无版本快照的存量执行为 NULL）' AFTER graph_snapshot,
    ADD COLUMN trigger_type VARCHAR(32) DEFAULT 'MANUAL' COMMENT '触发方式: MANUAL/API/AGENT_TOOL/SCHEDULE/WEBHOOK' AFTER session_id;

ALTER TABLE orchestration_node_execution
    ADD COLUMN retry_count INT DEFAULT 0 COMMENT '节点重试次数（成功前的额外尝试次数）' AFTER token_used;
