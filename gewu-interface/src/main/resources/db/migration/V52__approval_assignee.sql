-- ============================================================
-- V52: HITL 审批人圈定（EXEPLAN-ORCH-2026-09 / WFO-07）
-- HUMAN 节点 config.assigneeId/assigneeRole 经引擎透传落库，
-- 审批中心按 assigneeId 做待办可见性过滤（未配置=全员可见）。
-- ============================================================

ALTER TABLE approval_request
    ADD COLUMN assignee_id VARCHAR(26) DEFAULT NULL COMMENT '指定审批人（用户 ID，空=全员可见）' AFTER payload,
    ADD COLUMN assignee_role VARCHAR(64) DEFAULT NULL COMMENT '指定审批角色（角色编码，与 assignee_id 并用）' AFTER assignee_id;
