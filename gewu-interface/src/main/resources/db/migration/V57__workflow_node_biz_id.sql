-- ============================================================
-- V57: 节点业务 ID（P1 冒烟发现项修复）
-- 画布 nodeId 保存时被 ULID 重映射丢弃，导致表达式/回退目标/条件引用
-- 断桥（变量键是 ULID，表达式写的是画布 ID）。补 biz_node_id 列：
-- 保存时落画布 ID；调度器变量键与表达式解析优先用 biz_node_id。
-- ============================================================

ALTER TABLE workflow_node
    ADD COLUMN biz_node_id VARCHAR(64) DEFAULT NULL COMMENT '业务节点 ID（画布定义的 nodeId，表达式引用键）' AFTER workflow_id;

UPDATE workflow_node SET biz_node_id = id WHERE biz_node_id IS NULL;
CREATE INDEX idx_wf_node_biz ON workflow_node (workflow_id, biz_node_id);
