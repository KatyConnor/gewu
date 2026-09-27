-- ============================================================
-- V55: 工作流引擎 P1 内核扩展（docs/design/51 §三）
-- instance 加触发/终态/版本绑定；node_instance 加并行分支键/
-- 循环迭代/重试/超时语义；状态字面量订正为大写运行态。
-- ============================================================

ALTER TABLE workflow_instance
    ADD COLUMN trigger_type VARCHAR(32) DEFAULT 'MANUAL' COMMENT '触发: MANUAL/SCHEDULE/WEBHOOK/EVENT/UPSTREAM' AFTER initiator_id,
    ADD COLUMN final_output LONGTEXT COMMENT 'return 节点产出/终态输出' AFTER variables,
    ADD COLUMN error_message TEXT AFTER final_output,
    ADD COLUMN version_id VARCHAR(26) COMMENT '绑定的定义版本快照 ID' AFTER workflow_version;

ALTER TABLE workflow_node_instance
    ADD COLUMN branch_key VARCHAR(64) NOT NULL DEFAULT '' COMMENT '分支键（并行分支序号/loop 迭代上下文）' AFTER node_type,
    ADD COLUMN iteration INT NOT NULL DEFAULT 0 COMMENT '循环迭代序号' AFTER branch_key,
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数' AFTER output,
    ADD COLUMN error_message TEXT AFTER retry_count,
    ADD COLUMN timeout_at BIGINT COMMENT '等待型节点超时到期时间（定时器驱动）' AFTER completed_at,
    ADD UNIQUE KEY uk_wf_node_inst (instance_id, node_id, branch_key, iteration);
