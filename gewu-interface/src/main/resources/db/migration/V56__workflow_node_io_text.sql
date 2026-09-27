-- ============================================================
-- V56: 节点实例输入/输出列改自由文本（P1 冒烟发现项修复）
-- 节点输出承载任意文本（HTTP 响应体 / LLM 文本 / 表达式结果），
-- JSON 类型列拒绝非 JSON 文本；改 LONGTEXT 与编排轨 node_execution.output
-- 选型一致，结构化约定由应用层承担（表达式可解析 JSON 亦可读原文）。
-- ============================================================

ALTER TABLE workflow_node_instance
    MODIFY input LONGTEXT COMMENT '节点输入（任意文本，可为 JSON）',
    MODIFY output LONGTEXT COMMENT '节点输出（任意文本，可为 JSON）';
