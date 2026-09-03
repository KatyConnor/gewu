#!/usr/bin/env bash
# 格物平台压测执行脚本（T5.3）
# 用法: ./run-perf-test.sh [场景] （scenarios: login|sessions|sse|workflow|mix|all，默认 sse 冒烟）
set -euo pipefail

SERVER="${SERVER:-localhost}"
PORT="${PORT:-8080}"
USER="${USER:-jwt-test-user-1}"
PASSWORD="${PASSWORD:-Passw0rd!123}"
RESULTS_DIR="$(dirname "$0")/results"
mkdir -p "$RESULTS_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"

SCENARIO="${1:-sse}"
case "$SCENARIO" in
  login)    TG="01-登录（100并发/5min）" ;;
  sessions) TG="02-会话列表（200并发/10min）" ;;
  sse)      TG="03-AI流式对话（50并发/15min）" ;;
  workflow) TG="04-审批流（30并发/10min）" ;;
  mix)      TG="05-混合（750并发/30min）" ;;
  all)      TG="" ;;
  *) echo "未知场景: $SCENARIO"; exit 1 ;;
esac

ARGS=(-n -t "$(dirname "$0")/gewu-platform.jmx" \
      -JSERVER="$SERVER" -JPORT="$PORT" -JUSER="$USER" -JPASSWORD="$PASSWORD" \
      -l "$RESULTS_DIR/${SCENARIO}-${STAMP}.jtl" \
      -e -o "$RESULTS_DIR/${SCENARIO}-${STAMP}-report")

if [ -n "$TG" ]; then
  ARGS+=("-JthreadGroupFilter=$TG")
  # 按场景单线程组运行：通过仅启用该组实现（JMeter 支持 threadgroup 过滤属性）
  echo "执行场景: $SCENARIO -> $TG"
else
  echo "执行全部场景"
fi

if ! command -v jmeter >/dev/null; then
  echo "错误: 未安装 jmeter（brew install jmeter 或 apt install jmeter）"
  exit 1
fi

jmeter "${ARGS[@]}"
echo "结果: $RESULTS_DIR/${SCENARIO}-${STAMP}-report/index.html"
echo ""
echo "验收基线（PERFORMANCE-TEST-PLAN.md）:"
echo "  登录 P95 < 100ms | SSE 首字节 < 500ms 且完整 < 10s | 750 并发混合系统稳定"
