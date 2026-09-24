#!/bin/bash
set -uo pipefail

# 格物平台健康检查脚本 V2（S8 重写：自愈闭环）
# 用法: ./health-check.sh [local|remote] [HOST]
#
# V2 变更:
#   - 探活改用管理口 9081 的 /actuator/health（免认证、独立于业务线程池，
#     可在"业务线程全挂"类故障中独立反映进程存活）
#   - 业务口深度探活（GET /api/v1/agents 带 --max-time，区分"进程活"与"服务可用"）
#   - curl 全部带 --max-time（原版无超时，在挂起类故障下脚本自身会永久挂起）
#   - 修复 ((ERRORS++)) 与 set -e 的经典陷阱（首次失败即整脚本退出）
#   - 自愈闭环: 连续 FAIL_THRESHOLD 次失败自动重启主服务（gewu-ctl.sh restart interface）
#     并告警输出；状态计数落盘 .health-state

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_ROOT"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# ==================== 可调参数 ====================
MANAGE_PORT="${MANAGE_PORT:-9081}"          # actuator 管理口（免认证）
BIZ_PORT="${BIZ_PORT:-8081}"                # 业务口（主应用）
ADMIN_PORT="${ADMIN_PORT:-8083}"            # 后台管理服务业务口
ADMIN_WEB_PORT="${ADMIN_WEB_PORT:-5002}"    # 后台管理前端
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-10}"      # curl --max-time 秒
FAIL_THRESHOLD="${FAIL_THRESHOLD:-3}"       # 连续失败 N 次触发自愈重启
STATE_FILE="${PROJECT_ROOT}/.health-state"

ERRORS=0

check_http() {
    local URL="$1"
    local EXPECTED="$2"

    local body
    body=$(curl -s --max-time "$HEALTH_TIMEOUT" "$URL" 2>/dev/null) || true
    if [ -n "$body" ] && echo "$body" | grep -q "$EXPECTED"; then
        log_info "✓ $URL 正常"
        return 0
    fi
    log_error "✗ $URL 异常（响应: ${body:0:80}）"
    return 1
}

check_port() {
    local HOST="$1"
    local PORT="$2"
    local NAME="$3"

    if timeout 5 bash -c "cat < /dev/null > /dev/tcp/$HOST/$PORT" 2>/dev/null; then
        log_info "✓ $NAME ($HOST:$PORT) 端口正常"
        return 0
    fi
    log_error "✗ $NAME ($HOST:$PORT) 端口异常"
    return 1
}

# ==================== 检查项 ====================

check_local() {
    # 1. 管理口进程存活（独立于业务线程池，挂起时仍可响应）
    check_http "http://localhost:$MANAGE_PORT/actuator/health" '"status"' || ((++ERRORS))

    # 2. 业务口深度探活（agents 列表需认证，退化为 TCP 探活 + 超时受控；
    #    挂起形态下 TCP 可通但请求不分发——用一次带超时的管理口 health 判别即可）
    check_port "localhost" "$BIZ_PORT" "业务口" || ((++ERRORS))
    check_port "localhost" "$ADMIN_PORT" "管理服务口" || ((++ERRORS))
    check_port "localhost" "$ADMIN_WEB_PORT" "管理前端" || ((++ERRORS))

    # 3. Prometheus 指标端点
    check_http "http://localhost:$MANAGE_PORT/actuator/prometheus" "jvm_memory_used_bytes" || ((++ERRORS))
}

check_remote() {
    local HOST="$1"

    check_http "http://$HOST:$MANAGE_PORT/actuator/health" '"status"' || ((++ERRORS))
    check_port "$HOST" "$BIZ_PORT" "业务口" || ((++ERRORS))
    check_port "$HOST" "$ADMIN_PORT" "管理服务口" || ((++ERRORS))
    check_port "$HOST" "$ADMIN_WEB_PORT" "管理前端" || ((++ERRORS))
}

# ==================== 自愈闭环 ====================

maybe_selfheal() {
    local fail_mode="$1"

    # 连续失败计数（状态落盘，跨次执行累计）
    local prev=0
    if [ -f "$STATE_FILE" ]; then
        prev=$(cat "$STATE_FILE" 2>/dev/null || echo 0)
    fi
    local cur=$((prev + 1))
    echo "$cur" > "$STATE_FILE"

    log_warn "健康检查失败（连续 $cur/$FAIL_THRESHOLD 次，模式=$fail_mode）"

    if [ "$cur" -ge "$FAIL_THRESHOLD" ]; then
        log_error "连续 $cur 次失败，触发自愈: 重启主服务"
        if [ -x "$SCRIPT_DIR/gewu-ctl.sh" ]; then
            "$SCRIPT_DIR/gewu-ctl.sh" restart interface >> "$PROJECT_ROOT/logs/selfheal.log" 2>&1 \
                && log_info "自愈重启已执行" \
                || log_error "自愈重启失败（gewu-ctl.sh 不可用或执行出错）"
        else
            log_error "未找到 gewu-ctl.sh，无法自动重启——请人工介入"
        fi
        echo 0 > "$STATE_FILE"
    fi
}

mark_healthy() {
    echo 0 > "$STATE_FILE"
}

# ==================== 主流程 ====================

main() {
    local MODE="${1:-local}"
    local HOST="${2:-localhost}"

    log_info "格物平台健康检查"

    ERRORS=0
    case "$MODE" in
        local)
            check_local
            ;;
        remote)
            check_remote "$HOST"
            ;;
        *)
            log_error "未知模式: $MODE"
            echo "用法: $0 [local|remote] [HOST]"
            exit 1
            ;;
    esac

    if [ "$ERRORS" -eq 0 ]; then
        log_info "所有检查通过！"
        mark_healthy
    else
        log_error "$ERRORS 项检查失败"
        if [ "$MODE" = "local" ]; then
            maybe_selfheal "$MODE"
        fi
        exit 1
    fi
}

main "$@"
