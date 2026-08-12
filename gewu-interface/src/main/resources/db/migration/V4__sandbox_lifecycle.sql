-- V4: 沙箱生命周期管理字段 + 项目绑定 + Agent 自动创建支持
-- 关联文档: docs/design/27-agent-sandbox-design.md §11-§14 (V1.2)
-- 关联 PRD: docs/design/17-product-requirements.md §3.6 US-SB-05~12 (V1.1)

ALTER TABLE sandbox ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'manual'
    COMMENT '创建来源: manual/agent/project';
ALTER TABLE sandbox ADD COLUMN project_id VARCHAR(26) DEFAULT NULL
    COMMENT '关联项目ID (source=project时)';
ALTER TABLE sandbox ADD COLUMN agent_id VARCHAR(26) DEFAULT NULL
    COMMENT '关联AgentID (source=agent时)';
ALTER TABLE sandbox ADD COLUMN auto_destroy TINYINT DEFAULT 0
    COMMENT '是否自动销毁: 0=否 1=是 (Agent沙箱默认1)';
ALTER TABLE sandbox ADD COLUMN last_used_at BIGINT DEFAULT NULL
    COMMENT '最后使用时间(毫秒时间戳)';
ALTER TABLE sandbox ADD COLUMN expire_at BIGINT DEFAULT NULL
    COMMENT '过期时间(毫秒时间戳)';

ALTER TABLE sandbox ADD KEY idx_sandbox_project (project_id);
ALTER TABLE sandbox ADD KEY idx_sandbox_agent (agent_id);
ALTER TABLE sandbox ADD KEY idx_sandbox_source (source);
ALTER TABLE sandbox ADD KEY idx_sandbox_expire (expire_at);