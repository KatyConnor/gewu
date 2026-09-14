#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# 格物平台 - 服务启停控制脚本（Linux / macOS）
# 统一管理 gateway / interface / sandbox / web 四个服务，
# 并提供中间件（docker-compose）的启停入口。
#
# 用法:
#   ./scripts/gewu-ctl.sh <命令> [目标]
#
# 命令:
#   start     [all|gateway|interface|sandbox|web]   启动服务（默认 all）
#   stop      [all|gateway|interface|sandbox|web]   停止服务（默认 all）
#   restart   [all|gateway|interface|sandbox|web]   重启服务
#   status                                          查看全部服务状态
#   logs      [gateway|interface|sandbox|web] [-f]  查看服务日志
#   deps      [up|down|ps]                          中间件 docker-compose 管理
#
# 环境变量:
#   JAVA_HOME / NODE_HOME       工具链路径（未设置则从 PATH 查找）
#   JAVA_OPTS                   JVM 参数，默认 -Xms256m -Xmx1024m
#   SPRING_PROFILES_ACTIVE      Spring Profile（透传给后端服务）
#   GATEWAY_JAVA_OPTS 等        按服务覆盖 JVM 参数（<NAME>_JAVA_OPTS）
#
# 服务端口: gateway=8080  interface=8081  sandbox=8082  web=5001
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_ROOT"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

RUN_DIR="$PROJECT_ROOT/run"
LOG_DIR="$PROJECT_ROOT/logs"
mkdir -p "$RUN_DIR" "$LOG_DIR"

# 启动顺序：interface（被 web 代理依赖）→ sandbox → gateway → web
ALL_SERVICES=(interface sandbox gateway web)
BACKEND_SERVICES=(interface sandbox gateway)
MIDDLEWARE_PORTS=(3306 5432 6379)

# ------------------------------------------------------------
# 工具链
# ------------------------------------------------------------
setup_java() {
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        export PATH="$JAVA_HOME/bin:$PATH"
    fi
    command -v java &>/dev/null || { log_error "未找到 java，请安装 JDK 21 或设置 JAVA_HOME"; exit 1; }
}

setup_node() {
    if [[ -n "${NODE_HOME:-}" && -x "$NODE_HOME/bin/node" ]]; then
        export PATH="$NODE_HOME/bin:$PATH"
    fi
    command -v node &>/dev/null || { log_error "未找到 node，请安装 Node.js 18+ 或设置 NODE_HOME"; exit 1; }
}

# ------------------------------------------------------------
# 服务定义：jar 路径 / 端口 / 日志
# ------------------------------------------------------------
jar_of() {
    case "$1" in
        interface) echo "$PROJECT_ROOT/gewu-interface/target/gewu-interface-1.0.0-SNAPSHOT.jar" ;;
        sandbox)   echo "$PROJECT_ROOT/gewu-sandbox/target/gewu-sandbox-1.0.0-SNAPSHOT.jar" ;;
        gateway)   echo "$PROJECT_ROOT/gewu-gateway/target/gewu-gateway-1.0.0-SNAPSHOT.jar" ;;
    esac
}

port_of() {
    case "$1" in
        interface) echo 8081 ;;
        sandbox)   echo 8082 ;;
        gateway)   echo 8080 ;;
        web)       echo 5001 ;;
    esac
}

log_file_of() { echo "$LOG_DIR/gewu-$1.log"; }
pid_file_of() { echo "$RUN_DIR/$1.pid"; }

validate_service() {
    case "$1" in
        interface|sandbox|gateway|web) return 0 ;;
        *) log_error "未知服务: $1（可选: ${ALL_SERVICES[*]}）"; exit 1 ;;
    esac
}

# ------------------------------------------------------------
# 底层探活
# ------------------------------------------------------------
port_open() {
    (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null || return 1
    exec 3>&- 3<&- 2>/dev/null
    return 0
}

pid_alive() { [[ -n "$1" ]] && kill -0 "$1" 2>/dev/null; }

running_pid() {
    local pf
    pf="$(pid_file_of "$1")"
    if [[ -f "$pf" ]]; then
        local pid
        pid="$(cat "$pf" 2>/dev/null || true)"
        if pid_alive "$pid"; then
            echo "$pid"
            return 0
        fi
        rm -f "$pf"
    fi
    return 1
}

wait_port() { # wait_port <port> <timeout秒>
    local deadline=$((SECONDS + $2))
    while (( SECONDS < deadline )); do
        port_open "$1" && return 0
        sleep 1
    done
    return 1
}

# 递归收集并终止进程树（应对 pnpm/next 等子进程）
kill_tree() {
    local pid="$1" children child
    children="$(pgrep -P "$pid" 2>/dev/null || true)"
    for child in $children; do
        kill_tree "$child"
    done
    kill -TERM "$pid" 2>/dev/null || true
}

# ------------------------------------------------------------
# start / stop / status
# ------------------------------------------------------------
start_backend_service() {
    local svc="$1" jar port
    jar="$(jar_of "$svc")"
    port="$(port_of "$svc")"

    if running_pid "$svc" >/dev/null; then
        log_warn "$svc 已在运行（PID $(running_pid "$svc")），跳过"
        return 0
    fi
    if port_open "$port"; then
        log_warn "端口 $port 已被占用但无运行记录，请先排查后重试（stop $svc 或 kill 占用进程）"
        return 1
    fi
    if [[ ! -f "$jar" ]]; then
        log_error "jar 不存在: $jar，请先执行 ./scripts/package.sh backend"
        return 1
    fi

    local svc_opts_var="${svc^^}_JAVA_OPTS"
    local opts="${!svc_opts_var:-${JAVA_OPTS:--Xms256m -Xmx1024m -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/home/wnn/devcode/ai-code/gewu-platform/logs -Xlog:gc*:file=/home/wnn/devcode/ai-code/gewu-platform/logs/gc-$svc.log:time,uptime:filecount=5,filesize=20M}}"

    setup_java
    log_info "启动 $svc（端口 $port）..."
    nohup java $opts \
        ${SPRING_PROFILES_ACTIVE:+-Dspring.profiles.active=$SPRING_PROFILES_ACTIVE} \
        -jar "$jar" >> "$(log_file_of "$svc")" 2>&1 &
    local pid=$!
    echo "$pid" > "$(pid_file_of "$svc")"

    if wait_port "$port" 90; then
        log_info "$svc 启动成功（PID $pid，端口 $port 已就绪）"
    else
        log_error "$svc 端口 $port 在 90s 内未就绪，请查看日志: $(log_file_of "$svc")"
        return 1
    fi
}

start_web_service() {
    local port web_dir
    port="$(port_of web)"
    # 本机开发默认用源工程；部署场景可 WEB_DIR=/opt/gewu/web 指向 dist/web 产物
    web_dir="${WEB_DIR:-$PROJECT_ROOT/gewu-web}"

    if running_pid web >/dev/null; then
        log_warn "web 已在运行（PID $(running_pid web)），跳过"
        return 0
    fi
    if port_open "$port"; then
        log_warn "端口 $port 已被占用但无运行记录，请先排查后重试"
        return 1
    fi
    if [[ ! -d "$web_dir/.next" ]]; then
        log_error "$web_dir/.next 不存在，请先执行 ./scripts/package.sh web（或用 WEB_DIR 指定正确目录）"
        return 1
    fi

    setup_node
    if [[ ! -x "$web_dir/node_modules/.bin/next" ]] && ! command -v pnpm &>/dev/null; then
        log_error "未找到 pnpm 且 $web_dir/node_modules/.bin/next 不可用，无法启动 web"
        return 1
    fi
    log_info "启动 web（端口 $port，目录 $web_dir）..."
    (
        cd "$web_dir"
        if [[ -x node_modules/.bin/next ]]; then
            nohup ./node_modules/.bin/next start -p "$port" >> "$(log_file_of web)" 2>&1 &
        else
            nohup pnpm start >> "$(log_file_of web)" 2>&1 &
        fi
        echo $! > "$(pid_file_of web)"
    )
    local pid
    pid="$(cat "$(pid_file_of web)")"

    if wait_port "$port" 60; then
        log_info "web 启动成功（PID $pid，端口 $port 已就绪）"
    else
        log_error "web 端口 $port 在 60s 内未就绪，请查看日志: $(log_file_of web)"
        return 1
    fi
}

preflight_middleware() {
    for p in "${MIDDLEWARE_PORTS[@]}"; do
        if ! port_open "$p"; then
            log_warn "中间件端口 $p 未就绪，后端服务可能启动失败；可先执行: $0 deps up"
        fi
    done
}

do_start() {
    preflight_middleware
    local failed=0
    for svc in "$@"; do
        case "$svc" in
            interface|sandbox|gateway) start_backend_service "$svc" || failed=1 ;;
            web)                       start_web_service || failed=1 ;;
        esac
    done
    return $failed
}

do_stop() {
    local failed=0
    for svc in "$@"; do
        local pid
        if pid="$(running_pid "$svc")"; then
            log_info "停止 $svc（PID $pid）..."
            kill_tree "$pid"
            local waited=0
            while pid_alive "$pid" && (( waited < 30 )); do
                sleep 1; waited=$((waited + 1))
            done
            if pid_alive "$pid"; then
                log_warn "$svc 30s 未优雅退出，强制终止"
                kill -KILL "$pid" 2>/dev/null || true
            fi
            rm -f "$(pid_file_of "$svc")"
            log_info "$svc 已停止"
        else
            log_warn "$svc 未在运行"
        fi
    done
    return $failed
}

do_status() {
    printf '%-12s %-8s %-6s %s\n' "SERVICE" "PID" "PORT" "STATE"
    printf '%-12s %-8s %-6s %s\n' "-------" "---" "----" "-----"
    for svc in "${ALL_SERVICES[@]}"; do
        local pid state="STOPPED"
        if pid="$(running_pid "$svc")"; then
            state="RUNNING"
        elif port_open "$(port_of "$svc")"; then
            state="PORT_BUSY(外部进程)"
            pid="-"
        else
            pid="-"
        fi
        printf '%-12s %-8s %-6s %s\n' "$svc" "$pid" "$(port_of "$svc")" "$state"
    done
}

# ------------------------------------------------------------
# 中间件（docker-compose）
# ------------------------------------------------------------
compose_cmd() {
    if docker compose version &>/dev/null; then
        echo "docker compose"
    elif command -v docker-compose &>/dev/null; then
        echo "docker-compose"
    else
        log_error "未找到 docker compose，请先安装 Docker"
        exit 1
    fi
}

do_deps() {
    local action="${1:-ps}"
    local compose
    compose="$(compose_cmd)"
    case "$action" in
        up)    log_info "启动中间件（mysql/pgvector/minio/dragonfly/searxng）..."
               $compose -f "$PROJECT_ROOT/docker-compose.yml" up -d
               log_info "中间件已启动（MySQL:3306 PGVector:5432 MinIO:9000 Dragonfly:6379 SearXNG:8888）"
               ;;
        down)  $compose -f "$PROJECT_ROOT/docker-compose.yml" down ;;
        ps)    $compose -f "$PROJECT_ROOT/docker-compose.yml" ps ;;
        *)     log_error "deps 用法: $0 deps [up|down|ps]"; exit 1 ;;
    esac
}

# ------------------------------------------------------------
# 入口
# ------------------------------------------------------------
CMD="${1:-help}"
case "$CMD" in
    start)
        TARGET="${2:-all}"
        if [[ "$TARGET" == "all" ]]; then
            do_start "${ALL_SERVICES[@]}"
        else
            validate_service "$TARGET"
            do_start "$TARGET"
        fi
        ;;
    stop)
        TARGET="${2:-all}"
        if [[ "$TARGET" == "all" ]]; then
            # 与启动相反的顺序停止
            do_stop web gateway sandbox interface
        else
            validate_service "$TARGET"
            do_stop "$TARGET"
        fi
        ;;
    restart)
        TARGET="${2:-all}"
        "$0" stop "$TARGET" || true
        "$0" start "$TARGET"
        ;;
    status)
        do_status
        ;;
    logs)
        TARGET="${2:-web}"
        if [[ "${3:-}" == "-f" || "${2:-}" == "-f" ]]; then
            [[ "${2:-}" == "-f" ]] && TARGET="web"
            tail -f "$(log_file_of "$TARGET")"
        else
            tail -n 100 "$(log_file_of "$TARGET")"
        fi
        ;;
    deps)
        shift || true
        do_deps "${1:-ps}"
        ;;
    help|--help|-h)
        awk 'NR<=2{next} /^[[:space:]]*$/{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"
        ;;
    *)
        log_error "未知命令: $CMD（执行 $0 help 查看用法）"
        exit 1
        ;;
esac
