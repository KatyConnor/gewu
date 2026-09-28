-- ============================================================
-- V65: P3 二期 消息/事件等待型节点（53 号 §3.1/§3.3）
--   ① node_instance.message_key：receive-message 挂起登记，交付端点按此推进
--   ② instance.respond_payload：respond 节点产出，webhook 触发链路同步返回
--   ③ VLF_WORKFLOW_EVENT_SUBSCRIPTION：event-wait 订阅表（每事件类型一行）
-- 与 gewu-interface db/migration/V63 同基线
-- ============================================================

ALTER TABLE VLF_WORKFLOW_NODE_INSTANCE
    ADD COLUMN message_key VARCHAR(128) DEFAULT NULL COMMENT '消息等待关联键' AFTER assignee_role;

ALTER TABLE VLF_WORKFLOW_NODE_INSTANCE
    ADD INDEX idx_vlf_ni_message_key (message_key, status);

ALTER TABLE VLF_WORKFLOW_INSTANCE
    ADD COLUMN respond_payload LONGTEXT NULL COMMENT '同步响应载荷（respond 节点产出）' AFTER final_output;

CREATE TABLE IF NOT EXISTS VLF_WORKFLOW_EVENT_SUBSCRIPTION (
    id VARCHAR(26) NOT NULL COMMENT 'ULID',
    subscription_type VARCHAR(16) NOT NULL COMMENT 'WAIT=事件等待节点/TRIGGER=事件触发器节点',
    workflow_id VARCHAR(26) NOT NULL,
    instance_id VARCHAR(26) DEFAULT NULL COMMENT 'WAIT 订阅所属实例；TRIGGER 为 NULL',
    node_id VARCHAR(26) NOT NULL,
    node_instance_id VARCHAR(26) DEFAULT NULL COMMENT 'WAIT 订阅所属节点实例行；TRIGGER 为 NULL',
    event_type VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'waiting' COMMENT 'waiting/consumed/cancelled',
    tenant_id VARCHAR(26) DEFAULT 'default',
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_vlf_es_event (event_type, status),
    KEY idx_vlf_es_instance (instance_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Veloflow 事件订阅';
