#!/bin/bash
set -euo pipefail

# 格物平台数据库备份脚本
# 用法: ./backup.sh [full|incremental]

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

# 从 .env 加载配置
if [[ -f ".env" ]]; then
    source .env
fi

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-gewu_prod}"
DB_USER="${DB_USER:-root}"
DB_PASSWORD="${MYSQL_ROOT_PASSWORD:-}"

BACKUP_DIR="${BACKUP_DIR:-./backups}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"

backup_full() {
    local TIMESTAMP=$(date +%Y%m%d_%H%M%S)
    local BACKUP_FILE="$BACKUP_DIR/gewu_full_$TIMESTAMP.sql.gz"
    
    log_info "开始全量备份..."
    log_info "数据库: $DB_NAME@$DB_HOST:$DB_PORT"
    log_info "备份文件: $BACKUP_FILE"
    
    mkdir -p "$BACKUP_DIR"
    
    mysqldump -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p"$DB_PASSWORD" \
        --single-transaction \
        --routines \
        --triggers \
        --events \
        "$DB_NAME" | gzip > "$BACKUP_FILE"
    
    local SIZE=$(du -h "$BACKUP_FILE" | cut -f1)
    log_info "备份完成！文件大小: $SIZE"
    
    # 清理旧备份
    log_info "清理 $RETENTION_DAYS 天前的备份..."
    find "$BACKUP_DIR" -name "gewu_full_*.sql.gz" -mtime +$RETENTION_DAYS -delete
    
    log_info "备份清理完成"
}

backup_incremental() {
    log_warn "增量备份需要配置 binlog，请参考 MySQL 文档"
    log_info "建议使用全量备份 + binlog 恢复方案"
    backup_full
}

verify_backup() {
    local BACKUP_FILE="$1"
    
    log_info "验证备份文件: $BACKUP_FILE"
    
    if [[ ! -f "$BACKUP_FILE" ]]; then
        log_error "备份文件不存在"
        return 1
    fi
    
    # 测试解压
    if gzip -t "$BACKUP_FILE" 2>/dev/null; then
        log_info "✓ 备份文件完整性验证通过"
    else
        log_error "✗ 备份文件损坏"
        return 1
    fi
    
    # 检查文件大小
    local SIZE=$(stat -c%s "$BACKUP_FILE")
    if [[ $SIZE -gt 1000 ]]; then
        log_info "✓ 备份文件大小正常: $(du -h "$BACKUP_FILE" | cut -f1)"
    else
        log_warn "备份文件可能过小: $(du -h "$BACKUP_FILE" | cut -f1)"
    fi
}

restore_backup() {
    local BACKUP_FILE="$1"
    
    log_warn "即将恢复数据库: $DB_NAME"
    log_warn "此操作将覆盖现有数据！"
    read -p "确认恢复？(yes/no): " CONFIRM
    
    if [[ "$CONFIRM" != "yes" ]]; then
        log_info "恢复已取消"
        return 0
    fi
    
    log_info "开始恢复..."
    
    gunzip -c "$BACKUP_FILE" | mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p"$DB_PASSWORD" "$DB_NAME"
    
    log_info "恢复完成！"
}

main() {
    local ACTION="${1:-full}"
    local BACKUP_FILE="${2:-}"
    
    log_info "格物平台数据库备份工具"
    
    case "$ACTION" in
        full)
            backup_full
            ;;
        incremental)
            backup_incremental
            ;;
        verify)
            if [[ -z "$BACKUP_FILE" ]]; then
                log_error "请指定备份文件"
                exit 1
            fi
            verify_backup "$BACKUP_FILE"
            ;;
        restore)
            if [[ -z "$BACKUP_FILE" ]]; then
                log_error "请指定备份文件"
                exit 1
            fi
            restore_backup "$BACKUP_FILE"
            ;;
        *)
            log_error "未知操作: $ACTION"
            echo "用法: $0 [full|incremental|verify|restore] [BACKUP_FILE]"
            exit 1
            ;;
    esac
}

main "$@"
