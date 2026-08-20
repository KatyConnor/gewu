-- ============================================================
-- V31: 治理策略表（PolicyService 落地）
-- 按场景管理 Agent 行为策略，支持版本化与灰度激活/回滚。
-- rule_json 约定：
--   allow  : 允许的动作列表（非空时动作必须在列表内）
--   deny   : 明确禁止的动作列表
--   其他键 : 场景自定义参数（如 hitlThreshold、maxIterations）
-- ============================================================

CREATE TABLE IF NOT EXISTS governance_policy (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    scenario VARCHAR(64) NOT NULL COMMENT '策略场景: model_routing/tool_permission/hitl_threshold',
    policy_name VARCHAR(128) NOT NULL COMMENT '策略名称',
    rule_json LONGTEXT NOT NULL COMMENT '策略规则 JSON',
    version INT NOT NULL DEFAULT 1 COMMENT '版本号（同场景递增）',
    active TINYINT NOT NULL DEFAULT 0 COMMENT '是否当前生效',
    description VARCHAR(512) DEFAULT NULL COMMENT '策略说明',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间戳',
    updated_at BIGINT DEFAULT NULL COMMENT '更新时间戳',
    created_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    updated_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    KEY idx_gov_policy_scenario (scenario, active),
    KEY idx_gov_policy_version (scenario, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='治理策略（版本化/可回滚）';

-- 种子数据：三个核心场景的默认策略（version=1，active=1）
INSERT INTO governance_policy (id, scenario, policy_name, rule_json, version, active, description, deleted, created_at)
VALUES
('01J8POLICY000000000000000001', 'model_routing', '默认模型路由策略',
 '{"preferHighComplexityModel": true, "privacyFirst": false, "budgetAware": true}', 1, 1,
 '复杂任务优先强模型，预算受限时降级', 0, UNIX_TIMESTAMP() * 1000),
('01J8POLICY000000000000000002', 'tool_permission', '默认工具权限策略',
 '{"allow": ["search", "knowledge_query", "file_read"], "deny": ["file_write", "shell_exec", "network_external"]}', 1, 1,
 '默认只读工具放行，写操作与外联需授权', 0, UNIX_TIMESTAMP() * 1000),
('01J8POLICY000000000000000003', 'hitl_threshold', '默认 HITL 阈值策略',
 '{"confidenceThreshold": 0.75, "maxAutoIterations": 8, "forceHitlActions": ["delete_data", "send_external"]}', 1, 1,
 '置信度低于阈值或命中强制动作时转入人工审批', 0, UNIX_TIMESTAMP() * 1000);
