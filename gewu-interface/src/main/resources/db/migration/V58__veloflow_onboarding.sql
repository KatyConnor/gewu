-- ============================================================
-- V58: Veloflow 平台接入（52 号 P1-S3）
-- 1) 创建 VLF_ 全量流程引擎表（与 veloflow-engine db/init/veloflow_init.sql 同基线）
-- 2) 存量 workflow_* 数据迁移（INSERT SELECT，幂等可重放——INSERT IGNORE 语义
--    由唯一键/主键冲突忽略实现；OceanBase/MySQL 8 用 INSERT IGNORE）
-- 3) biz_node_id 已由 V57 在 workflow_node 上回填，随迁移带入
-- ============================================================

-- ========== 一、建表 ==========

CREATE TABLE IF NOT EXISTS VLF_WORKFLOW (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    workflow_name VARCHAR(128) NOT NULL COMMENT '流程名称',
    description TEXT COMMENT '描述',
    version INT NOT NULL DEFAULT 1 COMMENT '版本号',
    status TINYINT DEFAULT 0 COMMENT '0=草稿 1=已发布 2=已归档',
    category VARCHAR(64) COMMENT '分类',
    config JSON COMMENT '流程配置（状态机定义）',
    published_at BIGINT COMMENT '发布时间',
    tenant_id VARCHAR(26) DEFAULT 'default' COMMENT '租户（预留）',
    created_by VARCHAR(26), updated_by VARCHAR(26),
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_vlf_wf_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Veloflow 流程定义';

-- 2. 流程节点表
CREATE TABLE IF NOT EXISTS VLF_WORKFLOW_NODE (
    id VARCHAR(26) NOT NULL,
    workflow_id VARCHAR(26) NOT NULL COMMENT '流程 ID',
    biz_node_id VARCHAR(64) DEFAULT NULL COMMENT '业务节点 ID（画布定义，表达式引用键）',
    node_name VARCHAR(128) NOT NULL,
    node_type VARCHAR(32) NOT NULL COMMENT '节点类型（26 类注册表键）',
    config JSON COMMENT '节点配置',
    position_x FLOAT, position_y FLOAT,
    sort_order INT,
    tenant_id VARCHAR(26) DEFAULT 'default',
    created_by VARCHAR(26), updated_by VARCHAR(26),
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_vlf_node_wf (workflow_id),
    KEY idx_vlf_node_biz (workflow_id, biz_node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Veloflow 流程节点';

-- 3. 流程流转表
CREATE TABLE IF NOT EXISTS VLF_WORKFLOW_TRANSITION (
    id VARCHAR(26) NOT NULL,
    workflow_id VARCHAR(26) NOT NULL,
    from_node_id VARCHAR(64) NOT NULL COMMENT '源节点（biz_node_id 或行 ID）',
    to_node_id VARCHAR(64) NOT NULL,
    condition_expr TEXT COMMENT '流转条件表达式',
    label VARCHAR(64) COMMENT '流转标签（true/false/case 值/item/done）',
    sort_order INT,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_vlf_trans_wf (workflow_id),
    KEY idx_vlf_trans_from (from_node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Veloflow 流程流转';

-- 4. 流程实例表
CREATE TABLE IF NOT EXISTS VLF_WORKFLOW_INSTANCE (
    id VARCHAR(26) NOT NULL,
    workflow_id VARCHAR(26) NOT NULL,
    workflow_version INT NOT NULL DEFAULT 1,
    version_id VARCHAR(26) COMMENT '绑定的定义版本快照 ID',
    title VARCHAR(256),
    status VARCHAR(32) NOT NULL DEFAULT 'running' COMMENT 'running/completed/failed/suspended/terminated',
    initiator_id VARCHAR(26) NOT NULL COMMENT '发起人',
    trigger_type VARCHAR(32) DEFAULT 'MANUAL' COMMENT 'MANUAL/SCHEDULE/WEBHOOK/EVENT/UPSTREAM',
    current_node_id VARCHAR(64),
    variables JSON COMMENT '流程变量',
    final_output LONGTEXT COMMENT '终态输出',
    error_message TEXT,
    started_at BIGINT NOT NULL, completed_at BIGINT,
    tenant_id VARCHAR(26) DEFAULT 'default',
    created_by VARCHAR(26), updated_by VARCHAR(26),
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_vlf_inst_wf (workflow_id),
    KEY idx_vlf_inst_initiator (initiator_id, created_at),
    KEY idx_vlf_inst_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Veloflow 流程实例';

-- 5. 节点实例表
CREATE TABLE IF NOT EXISTS VLF_WORKFLOW_NODE_INSTANCE (
    id VARCHAR(26) NOT NULL,
    instance_id VARCHAR(26) NOT NULL,
    node_id VARCHAR(64) NOT NULL COMMENT '节点行 ID',
    branch_key VARCHAR(64) NOT NULL DEFAULT '' COMMENT '分支键（并行/循环上下文）',
    iteration INT NOT NULL DEFAULT 0 COMMENT '循环迭代序号',
    node_name VARCHAR(128) NOT NULL,
    node_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT 'pending/running/waiting/completed/failed/skipped/cancelled',
    assignee_id VARCHAR(26) COMMENT '办理人/审批人',
    input LONGTEXT COMMENT '节点输入（任意文本）',
    output LONGTEXT COMMENT '节点输出（任意文本，可为 JSON）',
    retry_count INT NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at BIGINT, completed_at BIGINT,
    timeout_at BIGINT COMMENT '等待型节点超时到期时间',
    remark TEXT,
    tenant_id VARCHAR(26) DEFAULT 'default',
    created_by VARCHAR(26), updated_by VARCHAR(26),
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_vlf_ni (instance_id, node_id, branch_key, iteration),
    KEY idx_vlf_ni_instance (instance_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Veloflow 节点实例';

-- 6. 通知表
CREATE TABLE IF NOT EXISTS VLF_WORKFLOW_NOTIFICATION (
    id VARCHAR(26) NOT NULL,
    instance_id VARCHAR(26) NOT NULL,
    node_instance_id VARCHAR(26),
    type VARCHAR(50) NOT NULL COMMENT 'TASK_CREATED/TASK_ASSIGNED/REVIEW_REQUIRED/TIMEOUT_WARNING',
    recipient_id VARCHAR(26) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    is_read TINYINT DEFAULT 0,
    sent_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_vlf_notif_recipient (recipient_id, is_read),
    KEY idx_vlf_notif_type (type, sent_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Veloflow 流程通知';

-- 7. 权限表
CREATE TABLE IF NOT EXISTS VLF_WORKFLOW_PERMISSION (
    id VARCHAR(26) NOT NULL,
    workflow_id VARCHAR(26) NOT NULL,
    role_code VARCHAR(50) NOT NULL COMMENT '角色编码',
    permission_type VARCHAR(20) NOT NULL COMMENT 'START/EXECUTE/REVIEW/MANAGE',
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_vlf_perm (workflow_id, role_code, permission_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Veloflow 流程权限';

-- 8. 审计日志表
CREATE TABLE IF NOT EXISTS VLF_WORKFLOW_AUDIT_LOG (
    id VARCHAR(26) NOT NULL,
    workflow_id VARCHAR(26) NOT NULL,
    instance_id VARCHAR(26),
    node_id VARCHAR(64),
    operation VARCHAR(50) NOT NULL COMMENT 'START/COMPLETE/REVIEW/REJECT/CANCEL/TIMEOUT',
    operator_id VARCHAR(26) NOT NULL,
    operator_name VARCHAR(50),
    before_state VARCHAR(20), after_state VARCHAR(20),
    ip_address VARCHAR(45),
    request_body TEXT, response_code INT, response_time BIGINT,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_vlf_audit_wf (workflow_id),
    KEY idx_vlf_audit_inst (instance_id),
    KEY idx_vlf_audit_operator (operator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Veloflow 流程审计日志';

-- 9. 定义版本快照表
CREATE TABLE IF NOT EXISTS VLF_WORKFLOW_VERSION (
    id VARCHAR(26) NOT NULL,
    workflow_id VARCHAR(26) NOT NULL,
    version INT NOT NULL,
    definition_snapshot LONGTEXT NOT NULL COMMENT 'nodes+transitions+config 不可变快照',
    published_by VARCHAR(26), published_at BIGINT,
    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_vlf_version (workflow_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Veloflow 定义版本快照';

-- 10. 定时触发配置表
CREATE TABLE IF NOT EXISTS VLF_WORKFLOW_SCHEDULE (
    id VARCHAR(26) NOT NULL,
    workflow_id VARCHAR(26) NOT NULL,
    cron_expr VARCHAR(64) NOT NULL,
    timezone VARCHAR(64) DEFAULT 'Asia/Shanghai',
    input_template VARCHAR(2048),
    enabled TINYINT DEFAULT 1,
    last_fire_at BIGINT, next_fire_at BIGINT,
    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_vlf_sched_wf (workflow_id),
    KEY idx_vlf_sched_fire (enabled, next_fire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Veloflow 定时触发配置';

-- 11. Webhook 触发配置表
CREATE TABLE IF NOT EXISTS VLF_WORKFLOW_WEBHOOK (
    id VARCHAR(26) NOT NULL,
    workflow_id VARCHAR(26) NOT NULL,
    token_hash VARCHAR(128) NOT NULL COMMENT 'token SM3 哈希（明文不落库）',
    enabled TINYINT DEFAULT 1,
    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_vlf_hook_token (token_hash),
    UNIQUE KEY uk_vlf_hook_wf (workflow_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Veloflow Webhook 触发配置';

-- ============================================================
-- 演进脚本目录：db/upgrade/（V2 起按版本追加，手工执行）
-- ============================================================


-- ========== 二、存量数据迁移（workflow_* → VLF_*） ==========

-- 定义（biz_node_id 由 V57 回填，画布 ID 与行 ID 一致）
INSERT IGNORE INTO VLF_WORKFLOW (id, workflow_name, description, version, status, category, config, published_at, tenant_id, created_by, updated_by, deleted, created_at, updated_at)
SELECT id, workflow_name, description, version, status, category, config, published_at, 'default', created_by, updated_by, deleted, created_at, updated_at FROM workflow;

INSERT IGNORE INTO VLF_WORKFLOW_NODE (id, workflow_id, biz_node_id, node_name, node_type, config, position_x, position_y, sort_order, tenant_id, created_by, updated_by, deleted, created_at, updated_at)
SELECT id, workflow_id, COALESCE(biz_node_id, id), node_name, node_type, config, position_x, position_y, sort_order, 'default', created_by, updated_by, deleted, created_at, updated_at FROM workflow_node;

INSERT IGNORE INTO VLF_WORKFLOW_TRANSITION (id, workflow_id, from_node_id, to_node_id, condition_expr, label, sort_order, created_at)
SELECT id, workflow_id, from_node_id, to_node_id, condition_expr, label, sort_order, created_at FROM workflow_transition;

-- 实例与节点实例（V55 扩展列随迁）
INSERT IGNORE INTO VLF_WORKFLOW_INSTANCE (id, workflow_id, workflow_version, version_id, title, status, initiator_id, trigger_type, current_node_id, variables, final_output, error_message, started_at, completed_at, tenant_id, created_by, updated_by, deleted, created_at, updated_at)
SELECT id, workflow_id, workflow_version, version_id, title, status, initiator_id, COALESCE(trigger_type, 'MANUAL'), current_node_id, variables, final_output, error_message, started_at, completed_at, 'default', created_by, updated_by, deleted, created_at, updated_at FROM workflow_instance;

INSERT IGNORE INTO VLF_WORKFLOW_NODE_INSTANCE (id, instance_id, node_id, branch_key, iteration, node_name, node_type, status, assignee_id, input, output, retry_count, error_message, started_at, completed_at, timeout_at, remark, tenant_id, created_by, updated_by, deleted, created_at, updated_at)
SELECT id, instance_id, node_id, branch_key, iteration, node_name, node_type, status, assignee_id, input, output, retry_count, error_message, started_at, completed_at, timeout_at, remark, 'default', created_by, updated_by, deleted, created_at, updated_at FROM workflow_node_instance;

INSERT IGNORE INTO VLF_WORKFLOW_NOTIFICATION (id, instance_id, node_instance_id, type, recipient_id, title, content, is_read, sent_at, created_at)
SELECT id, instance_id, node_instance_id, type, recipient_id, title, content, is_read, sent_at, created_at FROM workflow_notification;

INSERT IGNORE INTO VLF_WORKFLOW_AUDIT_LOG (id, workflow_id, instance_id, node_id, operation, operator_id, operator_name, before_state, after_state, ip_address, request_body, response_code, response_time, created_at)
SELECT id, workflow_id, instance_id, node_id, operation, operator_id, operator_name, before_state, after_state, ip_address, request_body, response_code, response_time, created_at FROM workflow_audit_log;
