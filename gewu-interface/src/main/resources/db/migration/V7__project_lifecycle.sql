-- =====================================================
-- V7: 项目生命周期管理 — 阶段/文档/版本控制
-- =====================================================

-- 1. project 表新增字段
   ALTER TABLE project ADD COLUMN project_code VARCHAR(64) COMMENT '项目编号';
   ALTER TABLE project ADD COLUMN current_phase VARCHAR(32) DEFAULT 'RESEARCH' COMMENT '当前阶段编码';
   ALTER TABLE project ADD COLUMN initiated_at BIGINT COMMENT '立项时间';
   ALTER TABLE project ADD COLUMN closed_at BIGINT COMMENT '结项时间';


-- ALTER TABLE project
--    ADD COLUMN IF NOT EXISTS current_phase VARCHAR(32) DEFAULT 'RESEARCH' COMMENT '当前阶段编码',
--    ADD COLUMN IF NOT EXISTS initiated_at BIGINT COMMENT '立项时间',
--    ADD COLUMN IF NOT EXISTS closed_at BIGINT COMMENT '结项时间';



-- 2. project_phase — 项目阶段表
CREATE TABLE IF NOT EXISTS project_phase (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    project_id VARCHAR(26) NOT NULL COMMENT '项目ID',
    phase_code VARCHAR(32) NOT NULL COMMENT '阶段编码',
    phase_order INT NOT NULL COMMENT '阶段顺序(1-17)',
    status TINYINT DEFAULT 0 COMMENT '0=未开始 1=进行中 2=已完成',
    started_at BIGINT COMMENT '开始时间',
    completed_at BIGINT COMMENT '完成时间',
    agent_id VARCHAR(26) COMMENT '关联 Agent ID',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) COMMENT '创建人',
    updated_by VARCHAR(26) COMMENT '更新人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_phase (project_id, phase_code),
    KEY idx_phase_order (project_id, phase_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目阶段表';

-- 3. phase_document — 阶段文档表（含审核字段）
CREATE TABLE IF NOT EXISTS phase_document (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    project_id VARCHAR(26) COMMENT '项目ID',
    phase_code VARCHAR(32) COMMENT '阶段编码',
    doc_name VARCHAR(256) NOT NULL COMMENT '文档名称',
    doc_type VARCHAR(32) COMMENT '文件类型(md/pdf/docx/image)',
    current_version INT DEFAULT 1 COMMENT '当前版本号',
    total_versions INT DEFAULT 1 COMMENT '总版本数',
    review_status VARCHAR(32) DEFAULT 'draft' COMMENT 'draft/pending/in_review/approved/rejected',
    reviewed_by VARCHAR(26) COMMENT '审核 Agent ID',
    reviewed_at BIGINT COMMENT '审核时间',
    review_comment TEXT COMMENT 'Agent 评审意见',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) COMMENT '创建人',
    updated_by VARCHAR(26) COMMENT '更新人',
    PRIMARY KEY (id),
    KEY idx_phase_doc (project_id, phase_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='阶段文档表';

-- 4. phase_document_version — 文档版本历史表
CREATE TABLE IF NOT EXISTS phase_document_version (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    document_id VARCHAR(26) NOT NULL COMMENT '文档ID',
    version_no INT NOT NULL COMMENT '版本号',
    file_url VARCHAR(1024) COMMENT 'MinIO 文件地址',
    content_md5 VARCHAR(64) COMMENT '内容摘要',
    change_summary VARCHAR(512) COMMENT '变更摘要',
    change_source VARCHAR(32) NOT NULL COMMENT 'manual(人工)/agent(AI)',
    agent_id VARCHAR(26) COMMENT '若为agent变更，记录Agent ID',
    agent_prompt TEXT COMMENT 'Agent操作提示词(审计)',
    file_size BIGINT COMMENT '文件大小(字节)',
    uploaded_by VARCHAR(26) COMMENT '上传人',
    uploaded_at BIGINT COMMENT '上传时间',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_doc_version (document_id, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档版本历史表';
