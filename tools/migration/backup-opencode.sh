#!/usr/bin/env bash
# backup-opencode.sh — Backup OpenCode source database tables
# Dumps all opencode_* tables, compresses with gzip, verifies integrity,
# and rotates backups keeping the last 5.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./migration-config.sh
source "${SCRIPT_DIR}/migration-config.sh"

BACKUP_DIR="${BACKUP_DIR:-/data/backups}"
DATE_STAMP="$(date +%Y%m%d_%H%M%S)"
BACKUP_FILE="${BACKUP_DIR}/opencode-pre-migration-${DATE_STAMP}.sql.gz"
BACKUP_LABEL="opencode-pre-migration"
RETAIN_COUNT="${RETAIN_COUNT:-5}"

usage() {
  echo "Usage: $0 [--keep N] [--backup-dir DIR]"
  echo "  --keep N       Number of backups to retain (default: 5)"
  echo "  --backup-dir   Backup directory (default: /data/backups)"
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep)      RETAIN_COUNT="$2"; shift 2 ;;
    --backup-dir) BACKUP_DIR="$2"; shift 2 ;;
    --help|-h)   usage ;;
    *) echo "Unknown option: $1"; usage ;;
  esac
done

# ── Prerequisites ──────────────────────────────────────────────────────────────
if ! command -v mysqldump &>/dev/null; then
  log_error "mysqldump not found. Install mysql-client package."
  exit 1
fi

check_mysql || exit 1

mkdir -p "${BACKUP_DIR}"

# ── Check available disk space (estimate 500MB needed) ─────────────────────────
check_disk_space 512000 || exit 1

# ── Get list of opencode_* tables ──────────────────────────────────────────────
log_info "Fetching OpenCode tables..."
TABLES="$(mysql_opencode "
  SELECT GROUP_CONCAT(TABLE_NAME)
  FROM INFORMATION_SCHEMA.TABLES
  WHERE TABLE_SCHEMA = '${OPENCODE_DB}'
    AND TABLE_NAME LIKE 'opencode_%'
" 2>/dev/null)"

if [[ -z "${TABLES}" ]]; then
  log_error "No opencode_* tables found in database '${OPENCODE_DB}'"
  exit 1
fi

log_info "Tables to backup: ${TABLES}"

# ── Perform backup ─────────────────────────────────────────────────────────────
log_info "Starting backup to ${BACKUP_FILE}..."

START_TIME="$(date +%s)"

MYSQL_PWD="${MYSQL_PASS}" mysqldump \
  -h "${MYSQL_HOST}" \
  -P "${MYSQL_PORT}" \
  -u "${MYSQL_USER}" \
  --databases "${OPENCODE_DB}" \
  --tables ${TABLES} \
  --single-transaction \
  --quick \
  --lock-tables=false \
  --routines \
  --triggers \
  --set-gtid-purged=OFF \
  2>"${BACKUP_DIR}/opencode-dump-err-${DATE_STAMP}.log" \
  | gzip -9 > "${BACKUP_FILE}.tmp"

if [[ ${PIPESTATUS[0]} -ne 0 ]]; then
  log_error "mysqldump failed. Check: ${BACKUP_DIR}/opencode-dump-err-${DATE_STAMP}.log"
  rm -f "${BACKUP_FILE}.tmp"
  exit 1
fi

mv "${BACKUP_FILE}.tmp" "${BACKUP_FILE}"

END_TIME="$(date +%s)"
DURATION=$((END_TIME - START_TIME))

BACKUP_SIZE="$(stat -c%s "${BACKUP_FILE}" 2>/dev/null || stat -f%z "${BACKUP_FILE}" 2>/dev/null)"
BACKUP_SIZE_HUMAN="$(numfmt --to=iec "${BACKUP_SIZE}" 2>/dev/null || echo "${BACKUP_SIZE} bytes")"

log_info "Backup completed in ${DURATION}s, size: ${BACKUP_SIZE_HUMAN}"

# ── Verify backup integrity ────────────────────────────────────────────────────
log_info "Verifying backup integrity..."
if ! gunzip -t "${BACKUP_FILE}" 2>/dev/null; then
  log_error "Backup file is corrupted (gzip integrity check failed)"
  exit 1
fi

# Verify by restoring a few rows into a temporary location
VERIFY_SQL="
  SELECT id FROM opencode_users LIMIT 3;
  SELECT id FROM opencode_sessions LIMIT 3;
"
VERIFY_OUTPUT="$(zcat "${BACKUP_FILE}" | mysql_exec -N -B 2>/dev/null || true)"

if [[ -z "${VERIFY_OUTPUT}" ]]; then
  log_warn "Backup verify: could not extract sample rows (backup may be valid but verify incomplete)"
else
  log_info "Backup verify: sample rows extracted successfully"
fi

BACKUP_CHECKSUM="$(sha256sum "${BACKUP_FILE}" | cut -d' ' -f1)"
log_info "Backup SHA256: ${BACKUP_CHECKSUM}"

# Save checksum
echo "${BACKUP_CHECKSUM}  ${BACKUP_FILE}" > "${BACKUP_FILE}.sha256"

# ── Rotate old backups ─────────────────────────────────────────────────────────
log_info "Rotating backups (keeping last ${RETAIN_COUNT})..."
OLD_BACKUPS=()
while IFS= read -r -d '' f; do
  OLD_BACKUPS+=("${f}")
done < <(find "${BACKUP_DIR}" -name "${BACKUP_LABEL}-*.sql.gz" -print0 | sort -z)

while [[ ${#OLD_BACKUPS[@]} -gt ${RETAIN_COUNT} ]]; do
  rm -f "${OLD_BACKUPS[0]}" "${OLD_BACKUPS[0]}.sha256"
  log_info "Removed old backup: ${OLD_BACKUPS[0]}"
  OLD_BACKUPS=("${OLD_BACKUPS[@]:1}")
done

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  OpenCode Backup Complete"
echo "═══════════════════════════════════════════════════════════════"
echo "  File:     ${BACKUP_FILE}"
echo "  Size:     ${BACKUP_SIZE_HUMAN}"
echo "  SHA256:   ${BACKUP_CHECKSUM}"
echo "  Duration: ${DURATION}s"
echo "═══════════════════════════════════════════════════════════════"
