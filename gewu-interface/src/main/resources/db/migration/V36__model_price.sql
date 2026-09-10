-- V36: 模型计费单价（T4.1 成本回填）
-- 按 model_config（模型级）而非 provider 级挂单价：同厂商不同模型价差大
-- （如 qwen-turbo vs qwen-plus）。缺省 0 = 不计费。

-- 列名须与实体字段 pricePer1kInput/pricePer1kOutput 的驼峰转下划线映射一致：
-- price_per1k_input / price_per1k_output（per 与 1k 之间不能加下划线）
ALTER TABLE model_config
    ADD COLUMN price_per1k_input DECIMAL(10,6) DEFAULT 0 COMMENT '输入每 1K token 单价（元）' AFTER status,
    ADD COLUMN price_per1k_output DECIMAL(10,6) DEFAULT 0 COMMENT '输出每 1K token 单价（元）' AFTER price_per1k_input;
