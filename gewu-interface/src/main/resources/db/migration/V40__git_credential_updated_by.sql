-- S9 F4：git_credential 补 updated_by（V20 建表遗漏；BaseEntity 统一审计列，
-- 开发沙箱创建路径的凭证查询因此失败——schema 漂移对齐）
DELIMITER $$
CREATE PROCEDURE add_column_if_missing_s9f4(IN tbl VARCHAR(64), IN col VARCHAR(64), IN ddl VARCHAR(512))
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = tbl AND column_name = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN ', ddl);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL add_column_if_missing_s9f4('git_credential', 'updated_by',
    '`updated_by` VARCHAR(26) DEFAULT NULL');

DROP PROCEDURE IF EXISTS add_column_if_missing_s9f4;
