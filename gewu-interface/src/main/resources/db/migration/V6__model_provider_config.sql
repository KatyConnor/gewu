-- ============================================================================
-- V6: 模型供应商与模型配置表
-- ============================================================================

-- 模型供应商表
CREATE TABLE IF NOT EXISTS model_provider (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    provider_code VARCHAR(64) NOT NULL COMMENT '供应商编码 (openai/anthropic/deepseek/qwen等)',
    provider_name VARCHAR(128) NOT NULL COMMENT '供应商显示名称',
    base_url VARCHAR(512) NOT NULL COMMENT 'API 基础地址',
    api_key VARCHAR(512) DEFAULT NULL COMMENT 'API 密钥（加密存储）',
    description VARCHAR(1024) DEFAULT NULL COMMENT '供应商描述',
    logo_letter VARCHAR(4) DEFAULT NULL COMMENT 'Logo 首字母',
    logo_color VARCHAR(64) DEFAULT NULL COMMENT 'Logo 渐变色 CSS 类',
    text_color VARCHAR(64) DEFAULT NULL COMMENT '文字色 CSS 类',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=启用 2=停用',
    sort_order INT DEFAULT 0 COMMENT '排序',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_provider_code (provider_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型供应商表';

-- 模型配置表
CREATE TABLE IF NOT EXISTS model_config (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    provider_id VARCHAR(26) NOT NULL COMMENT '供应商 ID',
    model_name VARCHAR(128) NOT NULL COMMENT '模型显示名称',
    model_id VARCHAR(128) NOT NULL COMMENT '模型标识 (如 gpt-4o, qwen-plus)',
    model_params JSON DEFAULT NULL COMMENT '模型参数 (temperature, max_tokens 等)',
    description VARCHAR(512) DEFAULT NULL COMMENT '模型描述',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=启用 2=停用',
    sort_order INT DEFAULT 0 COMMENT '排序',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    KEY idx_model_config_provider (provider_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型配置表';

-- 初始化默认供应商数据
INSERT INTO model_provider (id, provider_code, provider_name, base_url, api_key, description, logo_letter, logo_color, text_color, status, sort_order, created_at, updated_at, created_by) VALUES
('01JZMPRV0000000000000001', 'qwen', '通义千问', 'https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation', NULL, '阿里云出品的大语言模型，支持多轮对话与复杂推理', 'Q', 'from-amber-500/20 to-amber-600/10', 'text-amber-400', 1, 1, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01JZMPRV0000000000000002', 'deepseek', 'DeepSeek', 'https://api.deepseek.com/v1/chat/completions', NULL, '中国前沿 AI 公司，专注大语言模型与代码生成', 'D', 'from-purple-500/20 to-purple-600/10', 'text-purple-400', 2, 2, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01JZMPRV0000000000000003', 'openai', 'OpenAI', 'https://api.openai.com/v1', NULL, '全球领先的人工智能研究机构，提供 GPT 系列大模型', 'O', 'from-green-500/20 to-green-600/10', 'text-green-400', 2, 3, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01JZMPRV0000000000000004', 'anthropic', 'Anthropic', 'https://api.anthropic.com/v1', NULL, '专注于 AI 安全研究的公司，Claude 系列模型开发者', 'A', 'from-blue-500/20 to-blue-600/10', 'text-blue-400', 2, 4, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01JZMPRV0000000000000005', 'zhipu', '智谱 AI', 'https://open.bigmodel.cn/api/paas/v4', NULL, '清华大学技术团队打造，ChatGLM 系列模型提供者', 'Z', 'from-cyan-500/20 to-cyan-600/10', 'text-cyan-400', 2, 5, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01JZMPRV0000000000000006', 'doubao', '豆包', 'https://ark.cn-beijing.volces.com/api/v3', NULL, '字节跳动推出的智能助手，支持多模态交互', 'D', 'from-rose-500/20 to-rose-600/10', 'text-rose-400', 2, 6, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system');

-- 初始化默认模型数据
INSERT INTO model_config (id, provider_id, model_name, model_id, model_params, description, status, sort_order, created_at, updated_at, created_by) VALUES
('01JZMMDL0000000000000001', '01JZMPRV0000000000000001', 'Qwen Plus', 'qwen-plus', '{"temperature":0.7,"max_tokens":4096}', '通义千问 Plus，平衡性能与成本', 1, 1, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01JZMMDL0000000000000002', '01JZMPRV0000000000000001', 'Qwen Turbo', 'qwen-turbo', '{"temperature":0.7,"max_tokens":2048}', '通义千问 Turbo，快速响应', 1, 2, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01JZMMDL0000000000000003', '01JZMPRV0000000000000001', 'Qwen Max', 'qwen-max', '{"temperature":0.7,"max_tokens":4096}', '通义千问 Max，最强推理能力', 1, 3, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01JZMMDL0000000000000004', '01JZMPRV0000000000000002', 'DeepSeek Chat', 'deepseek-chat', '{"temperature":0.6,"max_tokens":4096}', 'DeepSeek V3 对话模型', 2, 1, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'),
('01JZMMDL0000000000000005', '01JZMPRV0000000000000002', 'DeepSeek Reasoner', 'deepseek-reasoner', '{"temperature":0.6,"max_tokens":4096}', 'DeepSeek R1 推理模型', 2, 2, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system');