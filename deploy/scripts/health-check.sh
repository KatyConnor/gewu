#!/bin/bash
set -euo pipefail

# 格物平台健康检查脚本
# 用法: ./health-check.sh [local|remote] [HOST]

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

check_http() {
    local URL="$1"
    local EXPECTED="$2"
    
    if curl -sf "$URL" | grep -q "$EXPECTED"; then
        log_info "✓ $URL 正常"
        return 0
    else
        log_error "✗ $URL 异常"
        return 1
    fi
}

check_port() {
    local HOST="$1"
    local PORT="$2"
    local NAME="$3"
    
    if nc -z "$HOST" "$PORT" 2>/dev/null; then
        log_info "✓ $NAME ($HOST:$PORT) 端口正常"
        return 0
    else
        log_error " $NAME ($HOST:$PORT) 端口异常"
        return 1
    fi
}

check_local() {
    log_info "检查本地服务..."
    
    local ERRORS=0
    
    # 检查应用端口
    check_port "localhost" "8080" "应用服务" || ((ERRORS++))
    
    # 检查健康接口
    check_http "http://localhost:8080/actuator/health" '"status":"UP"' || ((ERRORS++))
    
    # 检查 Prometheus 端点
    check_http "http://localhost:8080/actuator/prometheus" "jvm_memory_used_bytes" || ((ERRORS++))
    
    if [[ $ERRORS -eq 0 ]]; then
        log_info "所有检查通过！"
        return 0
    else
        log_error "$ERRORS 项检查失败"
        return 1
    fi
}

check_remote() {
    local HOST="${1:-localhost}"
    
    log_info "检查远程服务: $HOST"
    
    local ERRORS=0
    
    # 检查应用端口
    check_port "$HOST" "8080" "应用服务" || ((ERRORS++))
    
    # 检查健康接口
    check_http "http://$HOST:8080/actuator/health" '"status":"UP"' || ((ERRORS++))
    
    if [[ $ERRORS -eq 0 ]]; then
        log_info "所有检查通过！"
        return 0
    else
        log_error "$ERRORS 项检查失败"
        return 1
    fi
}

main() {
    local MODE="${1:-local}"
    local HOST="${2:-localhost}"
    
    log_info "格物平台健康检查"
    
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
}

main "$@"
