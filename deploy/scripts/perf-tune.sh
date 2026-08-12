#!/bin/bash
set -euo pipefail

# 格物平台性能调优脚本
# 用法: ./perf-tune.sh [check|tune|monitor]

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

check_system() {
    log_info "检查系统环境..."
    
    echo "=== 系统信息 ==="
    echo "主机名: $(hostname)"
    echo "内核版本: $(uname -r)"
    echo "CPU 核心数: $(nproc)"
    echo "总内存: $(free -h | awk '/Mem:/ {print $2}')"
    echo "可用内存: $(free -h | awk '/Mem:/ {print $7}')"
    echo "磁盘使用: $(df -h / | awk 'NR==2 {print $5}')"
    
    echo ""
    echo "=== Docker 信息 ==="
    docker version --format 'Server: {{.Server.Version}}' 2>/dev/null || echo "Docker 未运行"
    
    echo ""
    echo "=== 容器状态 ==="
    docker compose -f docker-compose.prod.yml ps 2>/dev/null || echo "容器未运行"
}

check_jvm() {
    log_info "检查 JVM 状态..."
    
    local CONTAINER="gewu-platform-prod"
    
    if ! docker ps --format '{{.Names}}' | grep -q "$CONTAINER"; then
        log_error "容器 $CONTAINER 未运行"
        return 1
    fi
    
    echo "=== JVM 内存 ==="
    docker exec "$CONTAINER" jcmd 1 VM.flags 2>/dev/null || echo "无法获取 JVM 标志"
    
    echo ""
    echo "=== 堆内存使用 ==="
    docker exec "$CONTAINER" jcmd 1 GC.heap_info 2>/dev/null || echo "无法获取堆信息"
    
    echo ""
    echo "=== 线程数 ==="
    docker exec "$CONTAINER" jcmd 1 Thread.print 2>/dev/null | grep -c "nid=" || echo "无法获取线程信息"
}

check_database() {
    log_info "检查数据库状态..."
    
    echo "=== MySQL 连接数 ==="
    docker exec gewu-mysql-prod mysql -u root -p${MYSQL_ROOT_PASSWORD:-root123456} -e "SHOW STATUS LIKE 'Threads_connected';" 2>/dev/null || echo "无法连接 MySQL"
    
    echo ""
    echo "=== 慢查询 ==="
    docker exec gewu-mysql-prod mysql -u root -p${MYSQL_ROOT_PASSWORD:-root123456} -e "SHOW STATUS LIKE 'Slow_queries';" 2>/dev/null || echo "无法获取慢查询"
    
    echo ""
    echo "=== 连接池状态 ==="
    docker exec gewu-mysql-prod mysql -u root -p${MYSQL_ROOT_PASSWORD:-root123456} -e "SHOW STATUS LIKE 'Max_used_connections';" 2>/dev/null || echo "无法获取连接池状态"
}

check_redis() {
    log_info "检查 Redis 状态..."
    
    echo "=== Redis 信息 ==="
    docker exec gewu-dragonfly-prod redis-cli INFO memory 2>/dev/null | grep -E "used_memory_human|maxmemory_human" || echo "无法获取 Redis 信息"
    
    echo ""
    echo "=== Redis 连接数 ==="
    docker exec gewu-dragonfly-prod redis-cli INFO clients 2>/dev/null | grep connected_clients || echo "无法获取连接数"
    
    echo ""
    echo "=== Redis 命中率 ==="
    local HITS=$(docker exec gewu-dragonfly-prod redis-cli INFO stats 2>/dev/null | grep keyspace_hits | cut -d: -f2)
    local MISSES=$(docker exec gewu-dragonfly-prod redis-cli INFO stats 2>/dev/null | grep keyspace_misses | cut -d: -f2)
    if [[ -n "$HITS" && -n "$MISSES" && "$HITS" -gt 0 ]]; then
        local RATE=$(echo "scale=2; $HITS / ($HITS + $MISSES) * 100" | bc)
        echo "缓存命中率: ${RATE}%"
    fi
}

tune_docker() {
    log_info "优化 Docker 配置..."
    
    # 优化内核参数
    echo "=== 优化内核参数 ==="
    echo "net.core.somaxconn = 65535" | sudo tee -a /etc/sysctl.conf
    echo "net.ipv4.tcp_max_syn_backlog = 65535" | sudo tee -a /etc/sysctl.conf
    echo "net.ipv4.ip_local_port_range = 1024 65535" | sudo tee -a /etc/sysctl.conf
    echo "net.ipv4.tcp_tw_reuse = 1" | sudo tee -a /etc/sysctl.conf
    echo "fs.file-max = 65535" | sudo tee -a /etc/sysctl.conf
    
    sudo sysctl -p
    
    log_info "Docker 配置优化完成"
}

monitor_realtime() {
    log_info "实时监控 (按 Ctrl+C 退出)..."
    
    while true; do
        clear
        echo "========================================="
        echo "  格物平台性能监控 - $(date)"
        echo "========================================="
        
        echo ""
        echo "=== 系统资源 ==="
        echo "CPU 使用: $(top -bn1 | grep "Cpu(s)" | awk '{print $2}')%"
        echo "内存使用: $(free -h | awk '/Mem:/ {print $3 "/" $2}')"
        echo "磁盘使用: $(df -h / | awk 'NR==2 {print $5}')"
        
        echo ""
        echo "=== 容器状态 ==="
        docker compose -f docker-compose.prod.yml ps --format "table {{.Name}}\t{{.Status}}\t{{.Ports}}" 2>/dev/null
        
        echo ""
        echo "=== JVM 指标 ==="
        local CONTAINER="gewu-platform-prod"
        if docker ps --format '{{.Names}}' | grep -q "$CONTAINER"; then
            docker exec "$CONTAINER" jcmd 1 GC.heap_info 2>/dev/null | head -5 || echo "无法获取 JVM 信息"
        fi
        
        echo ""
        echo "=== 按 Ctrl+C 退出 ==="
        sleep 5
    done
}

main() {
    local ACTION="${1:-check}"
    
    log_info "格物平台性能调优工具"
    
    case "$ACTION" in
        check)
            check_system
            check_jvm
            check_database
            check_redis
            ;;
        tune)
            tune_docker
            ;;
        monitor)
            monitor_realtime
            ;;
        *)
            log_error "未知操作: $ACTION"
            echo "用法: $0 [check|tune|monitor]"
            exit 1
            ;;
    esac
}

main "$@"
