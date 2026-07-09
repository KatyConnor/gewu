#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/migration-config.sh"

DRY_RUN=false
ARCHIVE_DIR="/data/archives/migration"

usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Post-migration cleanup: archive logs, remove temp files, drop tracking tables.

Options:
    --dry-run           Preview cleanup actions without executing
    --archive-dir DIR   Archive destination (default: /data/archives/migration)
    --keep-logs N       Keep last N log files (default: 5)
    --drop-tracking     Drop migration tracking tables
    -h, --help          Show this help

EOF
    exit 0
}

KEEP_LOGS=5
DROP_TRACKING=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) DRY_RUN=true; shift ;;
        --archive-dir) ARCHIVE_DIR="$2"; shift 2 ;;
        --keep-logs) KEEP_LOGS="$2"; shift 2 ;;
        --drop-tracking) DROP_TRACKING=true; shift ;;
        -h|--help) usage ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] [CLEANUP] $1"
}

log "========================================="
log "  Post-Migration Cleanup"
log "  Dry-run: $DRY_RUN"
log "========================================="

log ""
log "--- 1. Archive migration logs ---"
LOG_COUNT=$(find "$SCRIPT_DIR" -name "migration-*.log" -type f 2>/dev/null | wc -l)
log "Found $LOG_COUNT migration log files"

if [[ "$LOG_COUNT" -gt "$KEEP_LOGS" ]]; then
    TO_ARCHIVE=$((LOG_COUNT - KEEP_LOGS))
    log "Will archive $TO_ARCHIVE oldest log files"
    if [[ "$DRY_RUN" == "true" ]]; then
        find "$SCRIPT_DIR" -name "migration-*.log" -type f | sort | head -n "$TO_ARCHIVE" | while read -r f; do
            log "  DRY-RUN: Would archive $f -> $ARCHIVE_DIR/"
        done
    else
        mkdir -p "$ARCHIVE_DIR"
        find "$SCRIPT_DIR" -name "migration-*.log" -type f | sort | head -n "$TO_ARCHIVE" | while read -r f; do
            mv "$f" "$ARCHIVE_DIR/"
            log "  Archived: $(basename "$f")"
        done
    fi
else
    log "Log count ($LOG_COUNT) within keep limit ($KEEP_LOGS). Nothing to archive."
fi

log ""
log "--- 2. Remove temporary files ---"
TEMP_FILES=$(find "$SCRIPT_DIR" -name "*.tmp" -o -name "*.bak" -o -name "*checkpoint*" -type f 2>/dev/null | wc -l)
log "Found $TEMP_FILES temporary files"

if [[ "$TEMP_FILES" -gt 0 ]]; then
    if [[ "$DRY_RUN" == "true" ]]; then
        find "$SCRIPT_DIR" \( -name "*.tmp" -o -name "*.bak" -o -name "*checkpoint*" \) -type f | while read -r f; do
            log "  DRY-RUN: Would remove $f"
        done
    else
        find "$SCRIPT_DIR" \( -name "*.tmp" -o -name "*.bak" -o -name "*checkpoint*" \) -type f -delete
        log "  Removed $TEMP_FILES temporary files"
    fi
fi

log ""
log "--- 3. Migration tracking tables ---"
if [[ "$DROP_TRACKING" == "true" ]]; then
    TRACKING_TABLES=$(mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" -N -e \
        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'gewu' AND table_name LIKE 'migration_%';" 2>/dev/null || echo "")

    if [[ -n "$TRACKING_TABLES" ]]; then
        log "Found tracking tables: $TRACKING_TABLES"
        if [[ "$DRY_RUN" == "true" ]]; then
            echo "$TRACKING_TABLES" | while read -r t; do
                log "  DRY-RUN: Would DROP TABLE gewu.$t"
            done
        else
            echo "WARNING: This will permanently delete migration tracking data."
            read -rp "Are you sure? (yes/no): " confirm
            if [[ "$confirm" == "yes" ]]; then
                echo "$TRACKING_TABLES" | while read -r t; do
                    mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" \
                        -e "DROP TABLE gewu.${t};" 2>/dev/null
                    log "  Dropped: gewu.$t"
                done
            else
                log "Tracking table drop cancelled."
            fi
        fi
    else
        log "No migration tracking tables found."
    fi
else
    log "Skipping tracking table cleanup (use --drop-tracking to enable)"
fi

log ""
log "--- 4. Disk space summary ---"
SCRIPT_SIZE=$(du -sh "$SCRIPT_DIR" 2>/dev/null | cut -f1)
ARCHIVE_SIZE=$(du -sh "$ARCHIVE_DIR" 2>/dev/null | cut -f1 || echo "N/A")
log "Migration tools directory: $SCRIPT_SIZE"
log "Archive directory: $ARCHIVE_SIZE"

log ""
log "========================================="
log "Cleanup complete."
log "========================================="
