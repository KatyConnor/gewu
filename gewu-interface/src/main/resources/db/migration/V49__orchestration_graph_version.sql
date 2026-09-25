-- ============================================================
-- V49: 编排图版本化（EXEPLAN-ORCH-2026-09 / WFO-01）
-- 每次激活将当前图定义快照为不可变版本；执行记录绑定版本引用，
-- 保证历史执行回放不受后续再激活影响。存量 active 图由应用层
-- 在下次激活时写入 version 1，或执行加载时回退 graph_definition 兜底。
-- ============================================================

CREATE TABLE IF NOT EXISTS orchestration_graph_version (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    graph_id VARCHAR(26) NOT NULL COMMENT '编排图 ID',
    version INT NOT NULL COMMENT '版本号（同图内自增，从 1 起）',
    graph_definition LONGTEXT NOT NULL COMMENT '不可变版本快照 JSON',
    orchestration_mode VARCHAR(32) DEFAULT NULL COMMENT '快照时的编排模式',
    activated_by VARCHAR(26) DEFAULT NULL COMMENT '激活操作人',
    activated_at BIGINT DEFAULT NULL COMMENT '激活时间（毫秒）',
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_orch_graph_version (graph_id, version),
    KEY idx_orch_version_graph (graph_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='编排图版本快照（不可变）';
