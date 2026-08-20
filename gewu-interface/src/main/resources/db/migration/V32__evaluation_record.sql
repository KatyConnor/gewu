-- ============================================================
-- V32: 评测记录表（LlmJudge/SPC 评测管线）
-- 记录每次 LLM-as-Judge 评估结果，供评测历史查询与 SPC 劣化检测。
-- ============================================================

CREATE TABLE IF NOT EXISTS evaluation_record (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    execution_id VARCHAR(64) DEFAULT NULL COMMENT '关联执行实例 ID',
    case_id VARCHAR(64) DEFAULT NULL COMMENT '锚点用例 ID',
    score DOUBLE NOT NULL COMMENT '评估得分（0-10）',
    passed TINYINT NOT NULL DEFAULT 0 COMMENT '是否通过验收',
    verdict VARCHAR(1024) DEFAULT NULL COMMENT '评估说明',
    judge_provider VARCHAR(64) DEFAULT NULL COMMENT '评审模型供应商',
    judge_model VARCHAR(64) DEFAULT NULL COMMENT '评审模型',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间戳',
    updated_at BIGINT DEFAULT NULL COMMENT '更新时间戳',
    created_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    updated_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    KEY idx_eval_record_exec (execution_id),
    KEY idx_eval_record_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LlmJudge 评测记录';
