#!/usr/bin/env bash
# 统一启停入口：scripts/gewu-ctl.sh（start/stop/restart/status/logs）
# 用法保持兼容：./start-sandbox.sh 等价于 ./scripts/gewu-ctl.sh start sandbox
# 停止/重启/状态请使用：./scripts/gewu-ctl.sh stop|restart|status sandbox
set -euo pipefail
cd "$(dirname "$0")"
exec ./scripts/gewu-ctl.sh start sandbox "${@:2}"
