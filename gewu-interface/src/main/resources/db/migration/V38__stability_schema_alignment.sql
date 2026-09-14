-- V38: schema 漂移对齐（S8 稳定性专项）
-- 背景：既有库由旧版 deploy/scripts/V1__init_schema.sql 初始化且从未跑过 Flyway，
-- 部分实体必需/对齐列缺失。本迁移幂等（列存在则跳过），可安全重复执行。
-- 注意：agent_tool 的 created_by/updated_by 在既有库已手工补齐，本脚本的
-- 守卫逻辑对新装库补列、对既有库跳过，两端一致。

DELIMITER $$

DROP PROCEDURE IF EXISTS add_column_if_missing $$
CREATE PROCEDURE add_column_if_missing(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME = p_table
                     AND COLUMN_NAME = p_column) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$

DELIMITER ;

-- ===== A. ORM 必需（缺了会运行时报错） =====

-- api_key（ApiKey extends BaseEntity，INSERT 会填充 createdBy/updatedBy）
CALL add_column_if_missing('api_key', 'created_by', "VARCHAR(26) NULL COMMENT '创建人'");
CALL add_column_if_missing('api_key', 'updated_by', "VARCHAR(26) NULL COMMENT '更新人'");

-- sandbox_audit_log（SandboxAuditLog extends BaseSimpleEntity，INSERT 填充 createdAt；
-- 旧表只有 timestamp 列，缺 created_at 会在首次插入时直接报错）
CALL add_column_if_missing('sandbox_audit_log', 'created_at', "BIGINT NOT NULL DEFAULT 0 COMMENT '创建时间'");

-- agent_tool（既有库已手工补齐，此处守卫保证新装库走 Flyway 也一致）
CALL add_column_if_missing('agent_tool', 'created_by', "VARCHAR(26) NULL COMMENT '创建人'");
CALL add_column_if_missing('agent_tool', 'updated_by', "VARCHAR(26) NULL COMMENT '更新人'");

-- ===== B. 计划性对齐（统一补 created_by，MyBatis-Plus 不引用、零风险） =====

CALL add_column_if_missing('audit_log', 'created_by', "VARCHAR(26) NULL COMMENT '创建人'");
CALL add_column_if_missing('id_migration_map', 'created_by', "VARCHAR(26) NULL COMMENT '创建人'");
CALL add_column_if_missing('project_directory', 'created_by', "VARCHAR(26) NULL COMMENT '创建人'");
CALL add_column_if_missing('workflow_audit_log', 'created_by', "VARCHAR(26) NULL COMMENT '创建人'");
CALL add_column_if_missing('workflow_notification', 'created_by', "VARCHAR(26) NULL COMMENT '创建人'");
CALL add_column_if_missing('workflow_permission_matrix', 'created_by', "VARCHAR(26) NULL COMMENT '创建人'");
CALL add_column_if_missing('workflow_transition', 'created_by', "VARCHAR(26) NULL COMMENT '创建人'");

DROP PROCEDURE IF EXISTS add_column_if_missing;
