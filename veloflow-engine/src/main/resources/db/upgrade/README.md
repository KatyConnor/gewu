# Veloflow 版本演进脚本目录

- `veloflow_init.sql`（db/init/）为 1.0.0 全量基线；
- 后续版本变更脚本按 `veloflow_upgrade_<from>_to_<to>.sql` 命名追加于此，由使用方按序手工执行；
- 引擎自身不强制绑定 Flyway（宿主如已使用 Flyway，可将本目录纳入其迁移路径）。
- **铁律（2026-09-27 事故）**：迁移脚本一经在任一环境执行，**严禁再修改其内容**——
  Flyway 按 checksum 校验，改动会导致宿主启动失败（validate checksum mismatch）。
  修正性变更一律追加新版本脚本（V_n+1）。修复历史脚本的文本错误时，同步执行
  `flyway repair` 或将 `flyway_schema_history.checksum` 更新为本地新值（仅限
  DDL 实际效果未变的纯文本修正）。
