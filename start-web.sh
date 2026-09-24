#!/usr/bin/env bash
# 主应用前端前台启动（端口 5001）
set -e
cd "$(dirname "$0")"
mkdir -p logs

WEB_DIR="${WEB_DIR:-gewu-web}"
if [[ ! -d "$WEB_DIR/.next" ]]; then
    echo "[$0] 未找到 $WEB_DIR/.next，请先执行 ./scripts/package.sh web"
    exit 1
fi

echo "[$0] 启动 web（端口 5001，目录 $WEB_DIR）..."
cd "$WEB_DIR"
if [[ -x node_modules/.bin/next ]]; then
    ./node_modules/.bin/next start -p 5001
else
    pnpm start
fi
