#!/usr/bin/env bash
# 后台管理前端前台启动（独立端口 5002）
set -e
cd "$(dirname "$0")"
mkdir -p logs

WEB_DIR="${ADMIN_WEB_DIR:-gewu-admin-web}"
if [[ ! -d "$WEB_DIR/.next" ]]; then
    echo "[$0] 未找到 $WEB_DIR/.next，请先执行 ./scripts/package.sh admin-web"
    exit 1
fi

echo "[$0] 启动 admin-web（端口 5002，目录 $WEB_DIR）..."
cd "$WEB_DIR"
if [[ -x node_modules/.bin/next ]]; then
    ./node_modules/.bin/next start -p 5002
else
    pnpm start
fi
