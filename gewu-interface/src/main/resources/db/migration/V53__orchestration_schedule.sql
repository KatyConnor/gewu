-- ============================================================
-- V53: 编排图定时触发配置（EXEPLAN-ORCH-2026-09 / WFC-02）
-- 每图一条配置（uk: graph_id）；调度器每分钟扫描 next_fire_at 到期行，
-- CAS 抢占（UPDATE ... WHERE next_fire_at=旧值）防多实例重复触发，
-- 以系统身份发起执行（trigger_type=SCHEDULE）。
-- ============================================================

CREATE TABLE IF NOT EXISTS orchestration_schedule (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    graph_id VARCHAR(26) NOT NULL COMMENT '编排图 ID',
    cron_expr VARCHAR(64) NOT NULL COMMENT 'Cron 表达式（Spring CronExpression，6 位）',
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '时区',
    input_template VARCHAR(2048) DEFAULT NULL COMMENT '执行输入模板（原样作为 input）',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '启停开关',
    last_fire_at BIGINT DEFAULT NULL COMMENT '上次触发时间（毫秒）',
    next_fire_at BIGINT DEFAULT NULL COMMENT '下次触发时间（毫秒，CAS 抢占键）',
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_orch_schedule_graph (graph_id),
    KEY idx_orch_schedule_fire (enabled, next_fire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='编排图定时触发配置';
