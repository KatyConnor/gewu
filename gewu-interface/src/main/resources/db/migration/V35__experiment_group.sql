-- V35: A/B 实验分组支持（T3.4）
-- experiment_group 从 Agent modelConfig JSON 的 experimentGroup 字段解析写入，
-- 为空表示未参与实验（不参与对比聚合）。

ALTER TABLE agent_execution
    ADD COLUMN experiment_group VARCHAR(32) DEFAULT NULL COMMENT '实验分组（如 baseline/react_plus/full_stack）' AFTER tokens_used,
    ADD INDEX idx_agent_exec_group (experiment_group);
