#!/bin/bash
set -euo pipefail

# 格物平台回滚脚本
# 用法: ./rollback.sh [docker|k8s] [--to-version VERSION]

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

rollback_docker() {
    log_info "Docker Compose 回滚..."
    
    log_info "停止当前服务..."
    docker compose -f docker-compose.prod.yml down
    
    log_info "使用上一版本镜像重新启动..."
    # 假设使用标签版本控制
    docker compose -f docker-compose.prod.yml up -d
    
    log_info "回滚完成！"
    docker compose -f docker-compose.prod.yml ps
}

rollback_k8s() {
    local TO_VERSION="${1:-}"
    
    log_info "Kubernetes 回滚..."
    
    if [[ -n "$TO_VERSION" ]]; then
        log_info "回滚到版本: $TO_VERSION"
        kubectl -n gewu rollout undo deploy/gewu-platform --to-revision="$TO_VERSION"
    else
        log_info "回滚到上一版本..."
        kubectl -n gewu rollout undo deploy/gewu-platform
    fi
    
    log_info "等待回滚完成..."
    kubectl -n gewu rollout status deploy/gewu-platform --timeout=300s
    
    log_info "回滚完成！"
    kubectl -n gewu get pods
}

main() {
    local DEPLOY_MODE="${1:-docker}"
    local TO_VERSION="${2:-}"
    
    log_info "格物平台回滚"
    log_info "部署模式: $DEPLOY_MODE"
    
    case "$DEPLOY_MODE" in
        docker)
            rollback_docker
            ;;
        k8s)
            rollback_k8s "$TO_VERSION"
            ;;
        *)
            log_error "未知部署模式: $DEPLOY_MODE"
            echo "用法: $0 [docker|k8s] [VERSION]"
            exit 1
            ;;
    esac
}

main "$@"
