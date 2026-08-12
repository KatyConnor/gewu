#!/bin/bash
set -euo pipefail

# 格物平台生产环境部署脚本
# 用法: ./deploy.sh [docker|k8s]

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

check_prerequisites() {
    log_info "检查前置条件..."
    
    if ! command -v docker &> /dev/null; then
        log_error "Docker 未安装"
        exit 1
    fi
    
    if [[ "$DEPLOY_MODE" == "k8s" ]] && ! command -v kubectl &> /dev/null; then
        log_error "kubectl 未安装"
        exit 1
    fi
    
    if [[ ! -f ".env" ]]; then
        log_warn ".env 文件不存在，从 .env.example 复制..."
        cp .env.example .env
        log_warn "请编辑 .env 文件填写实际配置后重新运行"
        exit 1
    fi
    
    log_info "前置条件检查通过"
}

build_image() {
    log_info "构建 Docker 镜像..."
    docker build -t gewu/platform:1.0.0-SNAPSHOT .
    log_info "镜像构建完成"
}

deploy_docker() {
    log_info "使用 Docker Compose 部署..."
    
    check_prerequisites
    build_image
    
    log_info "启动服务..."
    docker compose -f docker-compose.prod.yml up -d
    
    log_info "等待服务启动..."
    sleep 10
    
    log_info "检查服务状态..."
    docker compose -f docker-compose.prod.yml ps
    
    log_info "部署完成！"
    log_info "访问地址: http://localhost:8080"
}

deploy_k8s() {
    log_info "使用 Kubernetes 部署..."
    
    check_prerequisites
    build_image
    
    # 推送镜像到仓库（需要配置）
    log_warn "请手动推送镜像到仓库: docker push gewu/platform:1.0.0-SNAPSHOT"
    
    log_info "应用 Kubernetes 配置..."
    kubectl apply -f deploy/k8s/namespace.yaml
    kubectl apply -f deploy/k8s/configmap.yaml
    kubectl apply -f deploy/k8s/secret.yaml
    kubectl apply -f deploy/k8s/deployment.yaml
    kubectl apply -f deploy/k8s/service.yaml
    kubectl apply -f deploy/k8s/ingress.yaml
    kubectl apply -f deploy/k8s/hpa.yaml
    kubectl apply -f deploy/k8s/monitoring.yaml
    
    log_info "等待部署完成..."
    kubectl -n gewu rollout status deploy/gewu-platform --timeout=300s
    
    log_info "部署完成！"
    kubectl -n gewu get pods,svc,ingress,hpa
}

main() {
    DEPLOY_MODE="${1:-docker}"
    
    log_info "格物平台生产环境部署"
    log_info "部署模式: $DEPLOY_MODE"
    
    case "$DEPLOY_MODE" in
        docker)
            deploy_docker
            ;;
        k8s)
            deploy_k8s
            ;;
        *)
            log_error "未知部署模式: $DEPLOY_MODE"
            echo "用法: $0 [docker|k8s]"
            exit 1
            ;;
    esac
}

main "$@"
