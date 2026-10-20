-- 入口调度文件功能集成测试发现的 schema 漂移修复：
-- WorkspaceFile 实体自 V75 同步功能起持有 volume_mtime/sync_status 两列
-- （V75 仅建了 workspace_sync_state 表，未对 workspace_file 补列），
-- 任何 workspace_file 查询在未手工补列的环境都会失败。守卫式补齐（对齐 V40 模式）。
DELIMITER $$
CREATE PROCEDURE add_column_if_missing_v79(IN tbl VARCHAR(64), IN col VARCHAR(64), IN ddl VARCHAR(512))
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

CALL add_column_if_missing_v79('workspace_file', 'volume_mtime',
    '`volume_mtime` BIGINT DEFAULT NULL COMMENT ''最近同步认定的卷侧mtime(秒,NULL=未同步过)''');
CALL add_column_if_missing_v79('workspace_file', 'sync_status',
    '`sync_status` TINYINT DEFAULT NULL COMMENT ''行级同步状态:0=干净 1=待同步 2=曾冲突''');

DROP PROCEDURE IF EXISTS add_column_if_missing_v79;
