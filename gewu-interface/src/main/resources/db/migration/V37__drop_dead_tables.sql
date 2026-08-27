-- V37: 清理 OpenCode 迁移遗留的空壳表（T4.4 死代码清理）
-- part / session_input / session_context_epoch 三表自迁移以来无任何业务读写
-- （仅有 Mapper 壳），实体与 Mapper 已删除；表结构可从 git 历史（V1 迁移）找回。

DROP TABLE IF EXISTS part;
DROP TABLE IF EXISTS session_input;
DROP TABLE IF EXISTS session_context_epoch;
