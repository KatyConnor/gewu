#!/usr/bin/env bash
# 统一启停入口：scripts/gewu-ctl.sh（start/stop/restart/status/logs）
# 用法保持兼容：./start-admin-web.sh 等价于 ./scripts/gewu-ctl.sh start admin-web
# 停止/重启/状态请使用：./scripts/gewu-ctl.sh stop|restart|status admin-web
set -euo pipefail
cd "$(dirname "$0")"
exec ./scripts/gewu-ctl.sh start admin-web "${@:2}"
