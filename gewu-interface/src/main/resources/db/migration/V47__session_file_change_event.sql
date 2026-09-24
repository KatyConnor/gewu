-- 会话文件写入事件表（文件变更统计修复）：
-- 此前 session_file_change 仅存"首次修改前"基线，列表统计按净差异计算，
-- 会话内创建的文件删除行结构性恒为 0、多次改写被坍缩为新建全量新增。
-- 改为每次写入累计一行事件（git churn 语义），撤销按 turn_seq 精确回退。
CREATE TABLE IF NOT EXISTS session_file_change_event (
  id         VARCHAR(26)  NOT NULL                COMMENT 'ULID',
  session_id VARCHAR(26)  NOT NULL                COMMENT '所属会话',
  file_path  VARCHAR(512) NOT NULL                COMMENT '相对工作空间根目录的文件路径',
  turn_seq   BIGINT       NOT NULL DEFAULT 0      COMMENT '产生本次写入的回合序号（0=回合外写入，如面板手工保存）',
  additions  INT          NOT NULL DEFAULT 0      COMMENT '本次写入新增行数',
  deletions  INT          NOT NULL DEFAULT 0      COMMENT '本次写入删除行数',
  created_at BIGINT       NOT NULL,
  PRIMARY KEY (id),
  KEY idx_sfc_event_session_path (session_id, file_path),
  KEY idx_sfc_event_session_turn (session_id, turn_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话文件写入事件（累计增删行数，撤销按回合回退）';
