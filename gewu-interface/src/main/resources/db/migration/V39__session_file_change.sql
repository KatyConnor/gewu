-- S9 F3：会话文件变更记录——支持右侧文件编辑面板的「本次会话编辑的所有文件」
-- 变更归属会话（唯一键 session_id+file_path），写类文件工具首次修改路径前记录 before 快照
-- 作为 diff 基线；当前内容按需从会话工作空间沙箱读取（无需冗余存储 after）。
CREATE TABLE IF NOT EXISTS session_file_change (
  id               VARCHAR(26)  NOT NULL                COMMENT 'ULID',
  session_id       VARCHAR(26)  NOT NULL                COMMENT '所属会话',
  user_id          VARCHAR(26)  NOT NULL                COMMENT '操作用户',
  file_path        VARCHAR(512) NOT NULL                COMMENT '相对工作空间根目录的文件路径',
  change_type      VARCHAR(16)  NOT NULL                COMMENT 'CREATE/MODIFY/DELETE',
  before_snapshot  MEDIUMTEXT                           COMMENT '首次修改前内容快照（diff 基线，新增文件为 NULL）',
  deleted          TINYINT      NOT NULL DEFAULT 0,
  created_at       BIGINT       NOT NULL,
  updated_at       BIGINT       NOT NULL,
  created_by       VARCHAR(26),
  updated_by       VARCHAR(26),
  PRIMARY KEY (id),
  UNIQUE KEY uk_sfc_session_file (session_id, file_path),
  KEY idx_sfc_session (session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='会话文件变更记录（S9 F3）';
