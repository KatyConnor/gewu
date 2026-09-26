-- ============================================================
-- V54: 编排图 Webhook 触发（EXEPLAN-ORCH-2026-09 / WFC-03）
-- 每图一条 Webhook 配置：token 明文仅创建/重置时返回一次，
-- 库内只存 SM3 哈希（uk: token_hash，按哈希索引查找天然抗时序探测）；
-- 匿名端点按哈希命中且 enabled=1 且图为 active 才触发（trigger_type=WEBHOOK），
-- 未命中/停用/关闭统一 404（不暴露存在性）。
-- ============================================================

CREATE TABLE IF NOT EXISTS orchestration_webhook (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    graph_id VARCHAR(26) NOT NULL COMMENT '编排图 ID',
    token_hash VARCHAR(128) NOT NULL COMMENT 'Webhook token 的 SM3 哈希（明文不落库）',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '启停开关',
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_orch_webhook_token (token_hash),
    UNIQUE KEY uk_orch_webhook_graph (graph_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='编排图 Webhook 触发配置';
