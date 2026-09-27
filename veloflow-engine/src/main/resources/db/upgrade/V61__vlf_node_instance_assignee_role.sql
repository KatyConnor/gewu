-- ============================================================
-- V61: 节点实例审批/办理角色列（52 号 P2 人工任务治理）
-- assignee_id 为空且 assignee_role 命中当前用户角色时，角色成员可办理；
-- 两者均空 = 任意登录用户可办。
-- ============================================================

ALTER TABLE VLF_WORKFLOW_NODE_INSTANCE
    ADD COLUMN assignee_role VARCHAR(64) DEFAULT NULL COMMENT '指派审批/办理角色' AFTER assignee_id;
