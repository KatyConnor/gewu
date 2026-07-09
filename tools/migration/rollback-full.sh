#!/usr/bin/env bash
# rollback-full.sh — Full rollback of gewu target database
# Stops migration processes, restores gewu from the pre-migration backup,
# runs verification, and logs all rollback details.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./migration-config.sh
source "${SCRIPT_DIR}/migration-config.sh"

BACKUP_DIR="${BACKUP_DIR:-/data/backups}"
ROLLBACK_LOG="${BACKUP_DIR}/rollback-full-$(date +%Y%m%d_%H%M%S).log"

usage() {
  echo "Usage: $0 [--backup FILE] [--force]"
  echo "  --backup FILE  Specific backup file to restore from (default: latest gewu backup)"
  echo "  --force        Skip confirmation prompt"
  exit 1
}

FORCE=false
RESTORE_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --backup) RESTORE_FILE="$2"; shift 2 ;;
    --force)  FORCE=true; shift ;;
    --help|-h) usage ;;
    *) echo "Unknown option: $1"; usage ;;
  esac
done

# ── Find backup file ───────────────────────────────────────────────────────────
if [[ -z "${RESTORE_FILE}" ]]; then
  RESTORE_FILE="$(find "${BACKUP_DIR}" -name 'gewu-pre-migration-*.sql.gz' -print0 \
    | sort -z \
    | tail -n1 \
    | tr -d '\0')"
fi

if [[ -z "${RESTORE_FILE}" ]] || [[ ! -f "${RESTORE_FILE}" ]]; then
  log_error "No gewu backup found at ${RESTORE_FILE:-${BACKUP_DIR}/gewu-pre-migration-*.sql.gz}"
  exit 1
fi

log_info "Using backup: ${RESTORE_FILE}"

# ── Confirmation ───────────────────────────────────────────────────────────────
if ! ${FORCE}; then
  echo ""
  echo "⚠️  FULL ROLLBACK — This will DESTROY all migration data in gewu database."
  echo "   Backup to restore: ${RESTORE_FILE}"
  echo "   Target database:   ${GEWU_DB}"
  echo ""
  read -r -p "Are you sure you want to proceed? [y/N] " confirm
  if [[ ! "${confirm}" =~ ^[Yy]$ ]]; then
    echo "Rollback cancelled."
    exit 1
  fi
fi

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  Full Rollback — $(date)"
echo "═══════════════════════════════════════════════════════════════"
echo "" | tee -a "${ROLLBACK_LOG}"

# ── Step 1: Stop any running migration processes ───────────────────────────────
log_info "Step 1: Stopping running migration processes..."

MIGRATION_PROCS="$(pgrep -f "migration" 2>/dev/null || true)"
if [[ -n "${MIGRATION_PROCS}" ]]; then
  echo "  Found migration processes: ${MIGRATION_PROCS}"
  kill ${MIGRATION_PROCS} 2>/dev/null || true
  sleep 2
  # Force kill any remaining
  MIGRATION_PROCS="$(pgrep -f "migration" 2>/dev/null || true)"
  if [[ -n "${MIGRATION_PROCS}" ]]; then
    kill -9 ${MIGRATION_PROCS} 2>/dev/null || true
    echo "  Force-stopped remaining processes." | tee -a "${ROLLBACK_LOG}"
  fi
  echo "  All migration processes stopped." | tee -a "${ROLLBACK_LOG}"
else
  echo "  No running migration processes found." | tee -a "${ROLLBACK_LOG}"
fi

# ── Step 2: Verify backup integrity ────────────────────────────────────────────
log_info "Step 2: Verifying backup integrity..."
if ! gunzip -t "${RESTORE_FILE}" 2>/dev/null; then
  log_error "Backup file is corrupted, aborting rollback"
  exit 1
fi
echo "  Backup integrity verified." | tee -a "${ROLLBACK_LOG}"

# ── Step 3: Backup current gewu state (emergency snapshot) ─────────────────────
log_info "Step 3: Taking emergency snapshot of current gewu state..."
EMERGENCY_BACKUP="${BACKUP_DIR}/gewu-emergency-$(date +%Y%m%d_%H%M%S).sql.gz"

MYSQL_PWD="${MYSQL_PASS}" mysqldump \
  -h "${MYSQL_HOST}" -P "${MYSQL_PORT}" -u "${MYSQL_USER}" \
  "${GEWU_DB}" \
  --single-transaction --quick --lock-tables=false \
  | gzip -9 > "${EMERGENCY_BACKUP}" || {
    log_warn "Emergency snapshot failed (non-fatal)"
    rm -f "${EMERGENCY_BACKUP}"
  }

if [[ -f "${EMERGENCY_BACKUP}" ]]; then
  echo "  Emergency snapshot: ${EMERGENCY_BACKUP}" | tee -a "${ROLLBACK_LOG}"
fi

# ── Step 4: Drop and restore gewu database ─────────────────────────────────────
log_info "Step 4: Restoring gewu database from backup..."

RESTORE_START="$(date +%s)"

# Drop existing tables
TABLES="$(mysql_exec -N -B "${GEWU_DB}" -e "
  SELECT GROUP_CONCAT(TABLE_NAME)
  FROM INFORMATION_SCHEMA.TABLES
  WHERE TABLE_SCHEMA = '${GEWU_DB}'
" 2>/dev/null || true)"

if [[ -n "${TABLES}" ]]; then
  log_info "  Dropping existing tables..."
  for table in $(echo "${TABLES}" | tr ',' '\n'); do
    mysql_exec "${GEWU_DB}" -e "DROP TABLE IF EXISTS \`${table}\`" 2>/dev/null || true
  done
  echo "  Existing tables dropped." | tee -a "${ROLLBACK_LOG}"
fi

# Restore from backup
zcat "${RESTORE_FILE}" | mysql_exec "${GEWU_DB}" 2>"${BACKUP_DIR}/restore-err-$(date +%Y%m%d_%H%M%S).log"

RESTORE_END="$(date +%s)"
RESTORE_DURATION=$((RESTORE_END - RESTORE_START))

echo "  Database restored in ${RESTORE_DURATION}s." | tee -a "${ROLLBACK_LOG}"

# ── Step 5: Verify restoration ─────────────────────────────────────────────────
log_info "Step 5: Verifying restoration..."

VERIFY_PASS=true

# Check key tables exist
for table in users sessions session_messages agents; do
  count="$(mysql_gewu "SELECT COUNT(*) FROM ${table}" 2>/dev/null || echo "MISSING")"
  if [[ "${count}" == "MISSING" ]]; then
    log_error "  Table ${table} is MISSING after restore"
    echo "  [FAIL] ${table}: table missing" | tee -a "${ROLLBACK_LOG}"
    VERIFY_PASS=false
  else
    echo "  [PASS] ${table}: ${count} rows" | tee -a "${ROLLBACK_LOG}"
  fi
done

# ── Step 6: Run rollback verification ──────────────────────────────────────────
log_info "Step 6: Running rollback verification..."
"${SCRIPT_DIR}/rollback-verify.sh" 2>&1 | tee -a "${ROLLBACK_LOG}" || true

# ── Summary ────────────────────────────────────────────────────────────────────
echo "" | tee -a "${ROLLBACK_LOG}"
echo "═══════════════════════════════════════════════════════════════" | tee -a "${ROLLBACK_LOG}"
echo "  Rollback Summary" | tee -a "${ROLLBACK_LOG}"
echo "═══════════════════════════════════════════════════════════════" | tee -a "${ROLLBACK_LOG}"
echo "  Restored from:  ${RESTORE_FILE}" | tee -a "${ROLLBACK_LOG}"
echo "  Duration:       ${RESTORE_DURATION}s" | tee -a "${ROLLBACK_LOG}"
echo "  Log:            ${ROLLBACK_LOG}" | tee -a "${ROLLBACK_LOG}"

if ${VERIFY_PASS}; then
  echo "  Result: ROLLBACK COMPLETE" | tee -a "${ROLLBACK_LOG}"
  exit 0
else
  echo "  Result: ROLLBACK COMPLETE WITH VERIFICATION WARNINGS" | tee -a "${ROLLBACK_LOG}"
  exit 1
fi
