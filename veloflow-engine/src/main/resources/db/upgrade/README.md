# Veloflow 版本演进脚本目录

- `veloflow_init.sql`（db/init/）为 1.0.0 全量基线；
- 后续版本变更脚本按 `veloflow_upgrade_<from>_to_<to>.sql` 命名追加于此，由使用方按序手工执行；
- 引擎自身不强制绑定 Flyway（宿主如已使用 Flyway，可将本目录纳入其迁移路径）。
