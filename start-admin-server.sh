#!/usr/bin/env bash
# 后台管理服务前台启动（独立端口 8083）
set -e
cd "$(dirname "$0")"
mkdir -p logs run

JAR="gewu-admin-server/target/gewu-admin-server-1.0.0-SNAPSHOT.jar"
if [[ ! -f "$JAR" ]]; then
    echo "[$name] 未找到 $JAR，请先执行 ./scripts/package.sh backend"
    exit 1
fi

echo "[$name] 启动 gewu-admin-server-1.0.0-SNAPSHOT.jar（端口 8083）..."
java -Xms256m -Xmx1024m -XX:+UseG1GC -jar "$JAR" > logs/gewu-admin-server.log 2>&1
