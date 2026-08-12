#!/bin/bash
set -euo pipefail

# 格物平台安全审计脚本
# 用法: ./security-audit.sh [full|quick|deps|container|network]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_ROOT"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[PASS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[FAIL]${NC} $1"; }

ISSUES=0

check_secrets() {
    log_info "检查敏感信息泄露..."
    
    # 检查硬编码密码
    if grep -rn "password\s*=\s*\"[^\"]*\"" --include="*.java" --include="*.yml" --include="*.yaml" --include="*.properties" . 2>/dev/null | grep -v "test" | grep -v "example"; then
        log_error "发现硬编码密码"
        ((ISSUES++))
    else
        log_info "无硬编码密码"
    fi
    
    # 检查 API Key
    if grep -rn "api[_-]key\s*=\s*\"[^\"]*\"" --include="*.java" --include="*.yml" --include="*.yaml" --include="*.properties" . 2>/dev/null | grep -v "test" | grep -v "example" | grep -v "\${"; then
        log_error "发现硬编码 API Key"
        ((ISSUES++))
    else
        log_info "无硬编码 API Key"
    fi
    
    # 检查 JWT Secret
    if grep -rn "jwt.*secret.*=.*\"[a-zA-Z0-9]" --include="*.java" --include="*.yml" --include="*.yaml" . 2>/dev/null | grep -v "test" | grep -v "example" | grep -v "\${"; then
        log_error "发现硬编码 JWT Secret"
        ((ISSUES++))
    else
        log_info "无硬编码 JWT Secret"
    fi
    
    # 检查私钥
    if grep -rn "private[_-]key\s*=\s*\"[^\"]*\"" --include="*.java" --include="*.yml" --include="*.yaml" . 2>/dev/null | grep -v "test" | grep -v "example"; then
        log_error "发现硬编码私钥"
        ((ISSUES++))
    else
        log_info "无硬编码私钥"
    fi
}

check_dependencies() {
    log_info "检查依赖安全..."
    
    # Maven 依赖检查
    if command -v mvn &> /dev/null; then
        log_info "检查 Maven 依赖漏洞..."
        mvn dependency-check:check -DfailBuildOnCVSS=7 2>/dev/null || log_warn "依赖检查未配置或有漏洞"
    fi
    
    # npm 依赖检查
    if command -v npm &> /dev/null && [ -f "gewu-web/package.json" ]; then
        log_info "检查 npm 依赖漏洞..."
        cd gewu-web
        npm audit --audit-level=high 2>/dev/null || log_warn "npm 依赖有高危漏洞"
        cd ..
    fi
}

check_container() {
    log_info "检查容器安全..."
    
    # Dockerfile 检查
    if [ -f "Dockerfile" ]; then
        # 检查是否使用 root 用户
        if grep -q "USER root" Dockerfile; then
            log_warn "Dockerfile 使用 root 用户"
            ((ISSUES++))
        else
            log_info "Dockerfile 使用非 root 用户"
        fi
        
        # 检查是否使用 latest 标签
        if grep -q "FROM.*:latest" Dockerfile; then
            log_warn "Dockerfile 使用 latest 标签"
        else
            log_info "Dockerfile 使用固定版本标签"
        fi
        
        # 检查是否有 HEALTHCHECK
        if grep -q "HEALTHCHECK" Dockerfile; then
            log_info "Dockerfile 包含 HEALTHCHECK"
        else
            log_warn "Dockerfile 缺少 HEALTHCHECK"
        fi
    fi
}

check_network() {
    log_info "检查网络安全..."
    
    # 检查 Kubernetes NetworkPolicy
    if [ -f "deploy/k8s/network-policy.yaml" ]; then
        log_info "NetworkPolicy 已配置"
    else
        log_warn "缺少 NetworkPolicy 配置"
    fi
    
    # 检查 Ingress TLS
    if [ -f "deploy/k8s/ingress.yaml" ]; then
        if grep -q "tls:" deploy/k8s/ingress.yaml; then
            log_info "Ingress 已配置 TLS"
        else
            log_warn "Ingress 未配置 TLS"
        fi
    fi
}

check_code() {
    log_info "检查代码安全..."
    
    # 检查 SQL 注入
    if grep -rn ".*\+.*\".*SELECT.*\".*\+" --include="*.java" . 2>/dev/null | grep -v "test" | grep -v "PreparedStatement"; then
        log_error "发现潜在 SQL 注入风险"
        ((ISSUES++))
    else
        log_info "无 SQL 注入风险"
    fi
    
    # 检查 XSS
    if grep -rn "innerHTML\|dangerouslySetInnerHTML" --include="*.tsx" --include="*.jsx" --include="*.vue" . 2>/dev/null | grep -v "test"; then
        log_warn "发现潜在 XSS 风险（innerHTML）"
    else
        log_info "无 XSS 风险"
    fi
    
    # 检查硬编码 IP
    if grep -rn "[0-9]\{1,3\}\.[0-9]\{1,3\}\.[0-9]\{1,3\}\.[0-9]\{1,3\}" --include="*.java" --include="*.yml" --include="*.yaml" . 2>/dev/null | grep -v "test" | grep -v "0.0.0.0" | grep -v "127.0.0.1"; then
        log_warn "发现硬编码 IP 地址"
    else
        log_info "无硬编码 IP 地址"
    fi
}

full_audit() {
    log_info "执行完整安全审计..."
    echo ""
    
    check_secrets
    check_dependencies
    check_container
    check_network
    check_code
    
    echo ""
    echo "========================================="
    echo "  安全审计完成"
    echo "  发现 $ISSUES 个问题"
    echo "========================================="
}

quick_audit() {
    log_info "执行快速安全审计..."
    echo ""
    
    check_secrets
    check_code
    
    echo ""
    echo "========================================="
    echo "  快速审计完成"
    echo "  发现 $ISSUES 个问题"
    echo "========================================="
}

main() {
    local ACTION="${1:-full}"
    
    log_info "格物平台安全审计工具"
    
    case "$ACTION" in
        full)
            full_audit
            ;;
        quick)
            quick_audit
            ;;
        secrets)
            check_secrets
            ;;
        deps)
            check_dependencies
            ;;
        container)
            check_container
            ;;
        network)
            check_network
            ;;
        *)
            log_error "未知操作: $ACTION"
            echo "用法: $0 [full|quick|secrets|deps|container|network]"
            exit 1
            ;;
    esac
}

main "$@"
