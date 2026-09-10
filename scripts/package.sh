#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# 格物平台 - 打包脚本（Linux / macOS）
# 打包后端（Spring Boot Maven 多模块）与前端（Next.js），
# 产物统一汇总到 dist/ 目录。
#
# 用法:
#   ./scripts/package.sh [all|backend|web] [选项]
#
# 选项:
#   --with-tests   执行后端单元测试（默认跳过）
#   --archive      打包完成后生成 tar.gz 归档
#   --clean        先清理旧的构建产物
#
# 环境变量:
#   JAVA_HOME      JDK 21 安装目录（如未配置则从 PATH 查找 java）
#   MAVEN_HOME     Maven 安装目录（如未配置则从 PATH 查找 mvn）
#   NODE_HOME      Node.js 安装目录（如未配置则从 PATH 查找 node/pnpm）
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
log_step()  { echo -e "${BLUE}[STEP]${NC} $1"; }

TARGET="all"
WITH_TESTS=false
ARCHIVE=false
CLEAN=false

for arg in "$@"; do
    case "$arg" in
        all|backend|web) TARGET="$arg" ;;
        --with-tests)    WITH_TESTS=true ;;
        --archive)       ARCHIVE=true ;;
        --clean)         CLEAN=true ;;
        -h|--help)
            awk 'NR<=2{next} /^[[:space:]]*$/{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"
            exit 0
            ;;
        *)
            log_error "未知参数: $arg（执行 $0 --help 查看用法）"
            exit 1
            ;;
    esac
done

# ------------------------------------------------------------
# 工具链探测：PATH 优先，其次 JAVA_HOME / MAVEN_HOME / NODE_HOME
# ------------------------------------------------------------
setup_java() {
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        export PATH="$JAVA_HOME/bin:$PATH"
    fi
    if ! command -v java &>/dev/null; then
        log_error "未找到 java。请安装 JDK 21，或设置 JAVA_HOME 环境变量。"
        exit 1
    fi
    local major
    major="$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
    if [[ "$major" -lt 21 ]]; then
        log_warn "当前 Java 版本为 $major，项目要求 Java 21，构建可能失败"
    fi
}

setup_maven() {
    if [[ -n "${MAVEN_HOME:-}" && -x "$MAVEN_HOME/bin/mvn" ]]; then
        export PATH="$MAVEN_HOME/bin:$PATH"
    fi
    if ! command -v mvn &>/dev/null; then
        log_error "未找到 mvn。请安装 Maven 3.8+，或设置 MAVEN_HOME 环境变量。"
        exit 1
    fi
}

setup_node() {
    if [[ -n "${NODE_HOME:-}" && -x "$NODE_HOME/bin/node" ]]; then
        export PATH="$NODE_HOME/bin:$PATH"
    fi
    if ! command -v node &>/dev/null; then
        log_error "未找到 node。请安装 Node.js 18+，或设置 NODE_HOME 环境变量。"
        exit 1
    fi
}

setup_pnpm() {
    command -v pnpm &>/dev/null && return 0
    # Node 自带 corepack，可一键启用 pnpm
    if command -v corepack &>/dev/null; then
        corepack enable pnpm 2>/dev/null || true
        command -v pnpm &>/dev/null && return 0
    fi
    log_error "未找到 pnpm。请执行 corepack enable pnpm 或 npm i -g pnpm 安装。"
    exit 1
}

# ------------------------------------------------------------
# 后端打包：gateway / interface / sandbox（-am 连带构建依赖模块）
# ------------------------------------------------------------
BACKEND_MODULES="gewu-gateway,gewu-interface,gewu-sandbox"
BACKEND_JARS=(
    "gewu-gateway/target/gewu-gateway-1.0.0-SNAPSHOT.jar"
    "gewu-interface/target/gewu-interface-1.0.0-SNAPSHOT.jar"
    "gewu-sandbox/target/gewu-sandbox-1.0.0-SNAPSHOT.jar"
)

build_backend() {
    setup_java
    setup_maven
    log_step "后端 Maven 打包（模块: $BACKEND_MODULES，测试: $WITH_TESTS）"

    local mvn_args=(-pl "$BACKEND_MODULES" -am package -ntp)
    if [[ "$WITH_TESTS" != true ]]; then
        mvn_args+=(-DskipTests)
    fi
    mvn "${mvn_args[@]}"

    mkdir -p dist/backend
    for jar in "${BACKEND_JARS[@]}"; do
        if [[ ! -f "$jar" ]]; then
            log_error "产物不存在: $jar"
            exit 1
        fi
        cp -f "$jar" dist/backend/
        log_info "已收集 $(basename "$jar") ($(du -h "$jar" | cut -f1))"
    done
}

# ------------------------------------------------------------
# 前端打包：Next.js 构建 + 运行时依赖收集
# ------------------------------------------------------------
build_web() {
    setup_node
    setup_pnpm
    log_step "前端 Next.js 打包（gewu-web，pnpm）"

    if [[ -f gewu-web/pnpm-lock.yaml ]]; then
        install_cmd=(pnpm install --frozen-lockfile)
        log_info "使用 pnpm 安装依赖（--frozen-lockfile）"
    else
        install_cmd=(pnpm install)
        log_warn "未找到 gewu-web/pnpm-lock.yaml，使用 pnpm install 安装依赖"
    fi

    ( cd gewu-web && "${install_cmd[@]}" )
    # --no-lint：ESLint 门禁在开发/CI 阶段执行，存量 lint 问题不阻塞产物打包
    ( cd gewu-web && pnpm exec next build --no-lint )

    log_info "收集前端运行时产物到 dist/web"
    rm -rf dist/web
    mkdir -p dist/web
    cp -a gewu-web/.next dist/web/.next
    cp -a gewu-web/public dist/web/public 2>/dev/null || mkdir -p dist/web/public
    cp -f gewu-web/package.json gewu-web/next.config.mjs dist/web/
    [[ -f gewu-web/pnpm-lock.yaml ]] && cp -f gewu-web/pnpm-lock.yaml dist/web/
    # next start 需要 node_modules（next 位于 dependencies 中）；
    # 用 pnpm 从本地 store 硬链接安装生产依赖，无需整份拷贝 node_modules
    log_info "在 dist/web 安装生产依赖（pnpm install --prod）"
    if ! ( cd dist/web && pnpm install --prod --frozen-lockfile --silent ); then
        log_warn "按锁文件安装失败（锁文件可能与 package.json 不同步），改用普通模式重装"
        ( cd dist/web && pnpm install --prod --silent )
    fi
    # .next/cache 是构建缓存，运行时不需要
    rm -rf dist/web/.next/cache
}

# ------------------------------------------------------------
# 主流程
# ------------------------------------------------------------
mkdir -p dist

if [[ "$CLEAN" == true ]]; then
    log_step "清理旧产物（dist/、Maven target/、Next.js .next/）"
    rm -rf dist
    if [[ "$TARGET" == "all" || "$TARGET" == "backend" ]]; then
        mvn -pl "$BACKEND_MODULES" -am clean -ntp -q || true
    fi
    if [[ "$TARGET" == "all" || "$TARGET" == "web" ]]; then
        rm -rf gewu-web/.next
    fi
fi

if [[ "$TARGET" == "all" || "$TARGET" == "backend" ]]; then
    build_backend
fi

if [[ "$TARGET" == "all" || "$TARGET" == "web" ]]; then
    build_web
fi

# 构建清单（版本号从根 pom.xml 的 project 块解析）
VERSION="$(awk '/<artifactId>gewu-platform<\/artifactId>/{f=1;next}
                f&&/<version>/{gsub(/.*<version>|<\/version>.*/,"");print;exit}' "$PROJECT_ROOT/pom.xml")"
[[ -z "$VERSION" ]] && VERSION="unknown"
GIT_SHA="$(git -C "$PROJECT_ROOT" rev-parse --short HEAD 2>/dev/null || echo "n/a")"
{
    echo "product=gewu-platform"
    echo "version=$VERSION"
    echo "git=$GIT_SHA"
    echo "built_at=$(date '+%Y-%m-%d %H:%M:%S')"
    echo "target=$TARGET"
    echo "with_tests=$WITH_TESTS"
} > dist/manifest.txt

if [[ "$ARCHIVE" == true ]]; then
    log_step "生成归档 dist/gewu-platform-${VERSION}-${GIT_SHA}.tar.gz"
    tar -czf "dist/gewu-platform-${VERSION}-${GIT_SHA}.tar.gz" -C dist \
        backend web manifest.txt
fi

echo
log_info "===== 打包完成 ====="
log_info "版本: $VERSION | Git: $GIT_SHA"
du -sh dist/backend dist/web 2>/dev/null || true
log_info "产物目录: $PROJECT_ROOT/dist"
log_info "后续操作: 使用 scripts/gewu-ctl.sh 启动服务（详见 scripts/README.md）"
