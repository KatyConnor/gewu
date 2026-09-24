-- V43: 用户套餐配额 + 用量流水 + 用户偏好 + 模型上下文/计价单位配置（配额与统计专项）
-- 设计要点：
--   1) 套餐 = 多时间窗口项（5小时滚动 / 自然周 / 自然月 / 自然季）各一个 token 总量上限；
--   2) 一条 usage_ledger 流水 = 一次执行（同步或流式）的 token/成本记账，窗口消耗由流水聚合；
--   3) 用户偏好承载提醒阈值与熔断开关（用户层，管理员套餐管总量）；
--   4) model_config 补上下文窗口与计价单位：单任务 token 上限由模型上下文窗口取代，
--      计价基准可配（1000/1000000 token 每单位），价格列沿用 V36 的 price_per1k_*。

-- ==================== 套餐 ====================
CREATE TABLE IF NOT EXISTS quota_plan (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    plan_name VARCHAR(128) NOT NULL COMMENT '套餐名称',
    description VARCHAR(512) DEFAULT NULL COMMENT '套餐描述',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=启用 2=停用',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    KEY idx_quota_plan_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户配额套餐';

CREATE TABLE IF NOT EXISTS quota_plan_item (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    plan_id VARCHAR(26) NOT NULL COMMENT '套餐 ID',
    window_type VARCHAR(16) NOT NULL COMMENT '窗口类型: FIVE_HOUR/WEEK/MONTH/QUARTER',
    token_limit BIGINT NOT NULL COMMENT '窗口内 token 总量上限',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_plan_item_window (plan_id, window_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='套餐时间窗口配额项';

CREATE TABLE IF NOT EXISTS user_quota_binding (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    user_id VARCHAR(26) NOT NULL COMMENT '用户 ID（一用户一生效套餐）',
    plan_id VARCHAR(26) NOT NULL COMMENT '套餐 ID',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=生效 2=停用',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人（绑定操作人）',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_binding (user_id),
    KEY idx_binding_plan (plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户套餐绑定';

-- ==================== 用量流水 ====================
CREATE TABLE IF NOT EXISTS usage_ledger (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    user_id VARCHAR(26) DEFAULT NULL COMMENT '用户 ID',
    session_id VARCHAR(26) DEFAULT NULL COMMENT '会话 ID',
    model_id VARCHAR(128) DEFAULT NULL COMMENT '模型标识',
    provider VARCHAR(64) DEFAULT NULL COMMENT '供应商标识',
    input_tokens INT DEFAULT 0 COMMENT '输入 tokens',
    output_tokens INT DEFAULT 0 COMMENT '输出 tokens',
    reasoning_tokens INT DEFAULT 0 COMMENT '推理 tokens',
    total_tokens INT DEFAULT 0 COMMENT '总 tokens',
    cost DECIMAL(12,6) DEFAULT 0 COMMENT '成本（元，按模型计价单位换算）',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '记账时间（毫秒）',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    KEY idx_ledger_user_time (user_id, created_at),
    KEY idx_ledger_model_time (model_id, created_at),
    KEY idx_ledger_time (created_at),
    KEY idx_ledger_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用量流水账本';

-- ==================== 用户偏好 ====================
CREATE TABLE IF NOT EXISTS user_preference (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    user_id VARCHAR(26) NOT NULL COMMENT '用户 ID',
    quota_alert_threshold INT DEFAULT 80 COMMENT '配额提醒阈值（百分比，跨阈值提醒一次）',
    quota_block_enabled TINYINT DEFAULT 0 COMMENT '配额熔断开关：1=耗尽后拒绝新任务 0=仅提醒',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_preference (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户偏好设置';

-- ==================== 模型上下文与计价单位 ====================
ALTER TABLE model_config
    ADD COLUMN context_window_input INT DEFAULT NULL COMMENT '上下文输入窗口（tokens，空=未知/不限制）' AFTER price_per1k_output,
    ADD COLUMN context_window_output INT DEFAULT NULL COMMENT '最大输出 tokens（空=不限制）' AFTER context_window_input,
    ADD COLUMN price_unit_tokens INT DEFAULT 1000 COMMENT '计价基准 token 数（价格列的计价单位，默认1000，可设1000000）' AFTER context_window_output;
