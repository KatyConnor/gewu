#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# 格物平台桌面端 - 编译打包脚本（macOS / Linux）
# 基于 electron-builder 产出各平台安装包，产物输出到 release/。
#
# 用法:
#   ./build.sh [选项]
#
# 选项:
#   --mac        打 macOS 包（dmg + zip，x64/arm64；仅限 macOS 上执行）
#   --win        打 Windows 包（nsis 安装包 + portable 免安装版，x64）
#   --linux      打 Linux 包（AppImage/deb/rpm/tar.gz，x64/arm64）
#   --all        打全平台包（受下面"跨平台限制"约束）
#   --arch <a>   指定架构: x64 | arm64 | all（默认跟随 electron-builder 配置）
#   --dir        仅输出未打包目录（release/<平台>-unpacked，调试用）
#
# 跨平台限制（由各 OS 官方工具链决定）:
#   - macOS 包（dmg/zip 签名相关产物）必须在 macOS 上构建
#   - Windows 包可在 macOS/Linux 上交叉构建（NSIS 由 electron-builder 内置）
#   - Linux 包可在任意平台交叉构建
#   Windows 用户请直接使用 build.ps1。
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

OS="$(uname -s)"
CURRENT_TARGET="linux"
[[ "$OS" == "Darwin" ]] && CURRENT_TARGET="mac"

TARGETS=()
ARCH=""
DIR_ONLY=false

while (( $# > 0 )); do
    case "$1" in
        --mac|--win|--linux) TARGETS+=("${1#--}") ;;
        --all)               TARGETS+=(mac win linux) ;;
        --arch)              shift; ARCH="${1:-}" ;;
        --dir)               DIR_ONLY=true ;;
        -h|--help)
            awk 'NR<=2{next} /^[[:space:]]*$/{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"
            exit 0
            ;;
        *) log_error "未知参数: $1（执行 $0 --help 查看用法）"; exit 1 ;;
    esac
    shift
done

# 默认打当前平台的包
if (( ${#TARGETS[@]} == 0 )); then
    TARGETS=("$CURRENT_TARGET")
    log_info "未指定目标平台，默认打包当前平台: $CURRENT_TARGET"
fi

for t in "${TARGETS[@]}"; do
    if [[ "$t" == "mac" && "$CURRENT_TARGET" != "mac" ]]; then
        log_error "macOS 包必须在 macOS 上构建（当前: $CURRENT_TARGET）。请在 Mac 上执行: $0 --mac"
        exit 1
    fi
done

# ------------------------------------------------------------
# 依赖安装：有锁文件走 npm ci，否则 npm install
# ------------------------------------------------------------
if ! command -v node &>/dev/null; then
    log_error "未找到 node。请安装 Node.js 18+ 或将其加入 PATH。"
    exit 1
fi

if [[ ! -d node_modules/electron ]]; then
    log_info "安装依赖（首次较慢，需下载 Electron 二进制）..."
    if [[ -f package-lock.json ]]; then
        npm ci
    else
        npm install
    fi
fi

# ------------------------------------------------------------
# 组装 electron-builder 参数并打包
# ------------------------------------------------------------
EB_ARGS=()
for t in "${TARGETS[@]}"; do
    EB_ARGS+=("--$t")
done
[[ -n "$ARCH" ]] && EB_ARGS+=("--$ARCH")
[[ "$DIR_ONLY" == true ]] && EB_ARGS+=("--dir")
EB_ARGS+=("--publish" "never")

log_info "开始打包: electron-builder ${EB_ARGS[*]}"
npx electron-builder "${EB_ARGS[@]}"

echo
log_info "===== 桌面端打包完成 ====="
log_info "产物目录: $SCRIPT_DIR/release"
if [[ "$DIR_ONLY" != true ]]; then
    du -h release/*.dmg release/*.exe release/*.AppImage release/*.deb release/*.rpm release/*.tar.gz 2>/dev/null || true
fi
