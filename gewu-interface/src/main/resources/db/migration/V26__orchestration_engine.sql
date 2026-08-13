-- ============================================================
-- V26: 编排引擎持久化
-- 创建编排图定义、执行实例、节点执行记录、审批请求四张表，
-- 使 OrchestrationEngine 从内存运行升级为可持久化、可追踪、可通过 API 调用。
-- ============================================================

-- 编排图定义表
CREATE TABLE IF NOT EXISTS orchestration_graph (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键（即 graphId）',
    graph_name VARCHAR(256) DEFAULT NULL COMMENT '编排图名称',
    graph_definition LONGTEXT COMMENT 'DAG JSON（nodes + edges + variables）',
    state_schema TEXT COMMENT '状态 Schema JSON',
    graph_type VARCHAR(32) NOT NULL DEFAULT 'AD_HOC' COMMENT '图类型: SDLC_PIPELINE/GOAL_DECOMPOSED/AD_HOC/TEMPLATE',
    orchestration_mode VARCHAR(32) NOT NULL DEFAULT 'PIPELINE' COMMENT '编排模式: SUPERVISOR/PIPELINE/SWARM/DEBATE',
    version VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '版本号',
    status VARCHAR(32) NOT NULL DEFAULT 'draft' COMMENT '状态: draft/active/archived',
    root_goal_id VARCHAR(26) DEFAULT NULL COMMENT '关联自主目标 ID',
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_orch_graph_status (status),
    KEY idx_orch_graph_root_goal (root_goal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='编排图定义';

-- 编排执行实例表
CREATE TABLE IF NOT EXISTS orchestration_execution (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键（即 executionId）',
    graph_id VARCHAR(26) DEFAULT NULL COMMENT '编排图 ID',
    graph_snapshot LONGTEXT COMMENT '执行时图快照 JSON（可重放）',
    user_id VARCHAR(26) DEFAULT NULL COMMENT '发起用户 ID',
    session_id VARCHAR(26) DEFAULT NULL COMMENT '会话 ID',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/RUNNING/PAUSED/SUCCEEDED/FAILED/CANCELLED',
    iteration_count INT DEFAULT 0 COMMENT '迭代次数',
    current_node_id VARCHAR(64) DEFAULT NULL COMMENT '当前执行节点 ID',
    variables LONGTEXT COMMENT '执行变量 JSON',
    final_output LONGTEXT COMMENT '最终输出',
    error_message TEXT COMMENT '错误信息',
    token_used BIGINT DEFAULT 0 COMMENT 'Token 消耗',
    cost_consumed DECIMAL(10,4) DEFAULT 0 COMMENT '成本消耗（元）',
    task_level VARCHAR(8) DEFAULT NULL COMMENT '任务等级 L1/L2/L3',
    started_at BIGINT DEFAULT NULL COMMENT '开始时间（毫秒）',
    completed_at BIGINT DEFAULT NULL COMMENT '完成时间（毫秒）',
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_orch_exec_graph (graph_id),
    KEY idx_orch_exec_status (status),
    KEY idx_orch_exec_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='编排执行实例';

-- 编排节点执行记录表
CREATE TABLE IF NOT EXISTS orchestration_node_execution (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    execution_id VARCHAR(26) NOT NULL COMMENT '编排执行实例 ID',
    node_id VARCHAR(64) NOT NULL COMMENT '节点 ID',
    node_type VARCHAR(32) NOT NULL COMMENT '节点类型: AGENT/TOOL/HUMAN/ROUTER/PARALLEL/MERGE/SUBGRAPH',
    role_code VARCHAR(64) DEFAULT NULL COMMENT '角色编码（AGENT 节点）',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/RUNNING/COMPLETED/FAILED/SKIPPED',
    input LONGTEXT COMMENT '节点输入 JSON',
    output LONGTEXT COMMENT '节点输出 JSON',
    token_used INT DEFAULT 0 COMMENT 'Token 消耗',
    duration_ms BIGINT DEFAULT NULL COMMENT '执行时长（毫秒）',
    error_message TEXT COMMENT '错误信息',
    started_at BIGINT DEFAULT NULL COMMENT '开始时间',
    completed_at BIGINT DEFAULT NULL COMMENT '完成时间',
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_orch_node_exec (execution_id),
    KEY idx_orch_node_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='编排节点执行记录';

-- 审批请求表
CREATE TABLE IF NOT EXISTS approval_request (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    execution_id VARCHAR(26) DEFAULT NULL COMMENT '编排执行实例 ID',
    node_id VARCHAR(64) NOT NULL COMMENT '触发审批的节点 ID',
    approval_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL_REVIEW' COMMENT '审批类型: MANUAL_REVIEW/TAKEOVER/ROLLBACK',
    payload LONGTEXT COMMENT '审批内容 JSON',
    status VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '状态: pending/approved/rejected/timeout',
    approver VARCHAR(26) DEFAULT NULL COMMENT '审批人',
    approval_comment TEXT COMMENT '审批意见',
    approved_at BIGINT DEFAULT NULL COMMENT '审批时间',
    timeout_at BIGINT NOT NULL COMMENT '超时时间',
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_approval_exec (execution_id),
    KEY idx_approval_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='HITL 审批请求';
