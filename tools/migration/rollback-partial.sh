#!/usr/bin/env bash
# rollback-partial.sh — Partial rollback by table
# Deletes migrated records from gewu for specified tables, optionally
# restores individual tables from backup, and verifies post-rollback state.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./migration-config.sh
source "${SCRIPT_DIR}/migration-config.sh"

BACKUP_DIR="${BACKUP_DIR:-/data/backups}"
ROLLBACK_LOG="${BACKUP_DIR}/rollback-partial-$(date +%Y%m%d_%H%M%S).log"
TABLES=""
RESTORE_FROM_BACKUP=false
FORCE=false

usage() {
  echo "Usage: $0 --tables users,sessions,messages [--restore] [--force]"
  echo "  --tables   Comma-separated list of tables to roll back"
  echo "  --restore  Restore tables from latest backup instead of just deleting"
  echo "  --force    Skip confirmation prompt"
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tables)  TABLES="$2"; shift 2 ;;
    --restore) RESTORE_FROM_BACKUP=true; shift ;;
    --force)   FORCE=true; shift ;;
    --help|-h) usage ;;
    *) echo "Unknown option: $1"; usage ;;
  esac
done

if [[ -z "${TABLES}" ]]; then
  echo "Error: --tables is required"
  usage
fi

IFS=',' read -ra TABLE_LIST <<< "${TABLES}"

# ── Validate tables exist ──────────────────────────────────────────────────────
log_info "Validating tables..."
for table in "${TABLE_LIST[@]}"; do
  exists="$(mysql_gewu "
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = '${GEWU_DB}' AND TABLE_NAME = '${table}'
  " 2>/dev/null || echo "0")"

  if [[ "${exists}" -eq 0 ]]; then
    log_error "Table '${table}' does not exist in gewu database"
    exit 1
  fi
  echo "  ✓ ${table} exists" | tee -a "${ROLLBACK_LOG}"
done

# ── Show row counts before rollback ────────────────────────────────────────────
echo "" | tee -a "${ROLLBACK_LOG}"
echo "Current row counts:" | tee -a "${ROLLBACK_LOG}"
for table in "${TABLE_LIST[@]}"; do
  count="$(mysql_gewu "SELECT COUNT(*) FROM ${table}")"
  echo "  ${table}: ${count} rows" | tee -a "${ROLLBACK_LOG}"
done

# ── Confirmation ───────────────────────────────────────────────────────────────
if ! ${FORCE}; then
  echo ""
  echo "⚠️  PARTIAL ROLLBACK — This will modify tables: ${TABLES}"
  if ${RESTORE_FROM_BACKUP}; then
    echo "   Mode: Restore from latest backup"
  else
    echo "   Mode: Delete migrated records only"
  fi
  echo ""
  read -r -p "Are you sure you want to proceed? [y/N] " confirm
  if [[ ! "${confirm}" =~ ^[Yy]$ ]]; then
    echo "Rollback cancelled."
    exit 1
  fi
fi

echo "" | tee -a "${ROLLBACK_LOG}"

# ── Perform rollback ───────────────────────────────────────────────────────────
ROLLBACK_START="$(date +%s)"

if ${RESTORE_FROM_BACKUP}; then
  # ── Restore individual tables from backup ────────────────────────────────────
  log_info "Restoring tables from latest backup..."

  RESTORE_FILE="$(find "${BACKUP_DIR}" -name 'gewu-pre-migration-*.sql.gz' -print0 \
    | sort -z | tail -n1 | tr -d '\0')"

  if [[ -z "${RESTORE_FILE}" ]]; then
    RESTORE_FILE="$(find "${BACKUP_DIR}" -name 'gewu-*.sql.gz' -print0 \
      | sort -z | tail -n1 | tr -d '\0')"
  fi

  if [[ -z "${RESTORE_FILE}" ]]; then
    log_error "No backup file found for table restore"
    exit 1
  fi

  log_info "Using backup: ${RESTORE_FILE}"

  # Extract and restore only specified tables
  TEMP_SQL="$(mktemp /tmp/rollback-partial.XXXXXX.sql)"
  zcat "${RESTORE_FILE}" | csplit -sz -f "${TEMP_SQL}.table." - "/-- Table structure for table/" "{*}" 2>/dev/null || true

  for table in "${TABLE_LIST[@]}"; do
    table_file=""
    table_file="$(grep -l "Table structure for table.*\`${table}\`" "${TEMP_SQL}".table.* 2>/dev/null || true)"

    if [[ -n "${table_file}" ]]; then
      log_info "  Restoring table: ${table}"
      # Drop and recreate the table
      mysql_exec "${GEWU_DB}" < "${table_file}" 2>>"${ROLLBACK_LOG}" || {
        log_error "  Failed to restore table ${table}"
        continue
      }
      echo "  ✓ ${table} restored from backup" | tee -a "${ROLLBACK_LOG}"
    else
      log_warn "  Table ${table} not found in backup, deleting records instead"
      mysql_gewu "DELETE FROM ${table}" 2>>"${ROLLBACK_LOG}"
      echo "  ✓ ${table} records deleted" | tee -a "${ROLLBACK_LOG}"
    fi
  done

  rm -f "${TEMP_SQL}".table.* "${TEMP_SQL}"

else
  # ── Delete records from specified tables (respect FK order) ──────────────────
  log_info "Deleting migration records..."

  # Define table dependency order (children first, parents last)
  declare -A TABLE_ORDER
  TABLE_ORDER[session_messages]=1
  TABLE_ORDER[messages]=1
  TABLE_ORDER[sessions]=2
  TABLE_ORDER[agents]=2
  TABLE_ORDER[users]=3

  SORTED_TABLES=()
  for table in "${TABLE_LIST[@]}"; do
    SORTED_TABLES+=("${table}")
  done

  # Sort by dependency order
  IFS=$'\n' SORTED_TABLES=(
    $(for t in "${SORTED_TABLES[@]}"; do
        echo "${TABLE_ORDER[${t}]:-0} ${t}"
      done | sort -n | awk '{print $2}')
  )

  for table in "${SORTED_TABLES[@]}"; do
    count_before="$(mysql_gewu "SELECT COUNT(*) FROM ${table}")"
    mysql_gewu "DELETE FROM ${table}" 2>>"${ROLLBACK_LOG}"
    count_after="$(mysql_gewu "SELECT COUNT(*) FROM ${table}")"
    deleted=$((count_before - count_after))
    echo "  ✓ ${table}: ${deleted} records deleted (${count_before} → ${count_after})" | tee -a "${ROLLBACK_LOG}"
  done
fi

ROLLBACK_END="$(date +%s)"
ROLLBACK_DURATION=$((ROLLBACK_END - ROLLBACK_START))

# ── Verification ───────────────────────────────────────────────────────────────
echo "" | tee -a "${ROLLBACK_LOG}"
log_info "Post-rollback verification..."

VERIFY_PASS=true
for table in "${TABLE_LIST[@]}"; do
  count="$(mysql_gewu "SELECT COUNT(*) FROM ${table}" 2>/dev/null || echo "ERROR")"
  if [[ "${count}" == "ERROR" ]]; then
    echo "  [FAIL] ${table}: query error" | tee -a "${ROLLBACK_LOG}"
    VERIFY_PASS=false
  else
    echo "  [PASS] ${table}: ${count} rows remaining" | tee -a "${ROLLBACK_LOG}"
  fi
done

# ── Summary ────────────────────────────────────────────────────────────────────
echo "" | tee -a "${ROLLBACK_LOG}"
echo "═══════════════════════════════════════════════════════════════" | tee -a "${ROLLBACK_LOG}"
echo "  Partial Rollback Summary" | tee -a "${ROLLBACK_LOG}"
echo "═══════════════════════════════════════════════════════════════" | tee -a "${ROLLBACK_LOG}"
echo "  Tables:     ${TABLES}" | tee -a "${ROLLBACK_LOG}"
echo "  Mode:       $(${RESTORE_FROM_BACKUP} && echo 'restore' || echo 'delete')" | tee -a "${ROLLBACK_LOG}"
echo "  Duration:   ${ROLLBACK_DURATION}s" | tee -a "${ROLLBACK_LOG}"

if ${VERIFY_PASS}; then
  echo "  Result: PARTIAL ROLLBACK COMPLETE" | tee -a "${ROLLBACK_LOG}"
  exit 0
else
  echo "  Result: PARTIAL ROLLBACK WITH WARNINGS" | tee -a "${ROLLBACK_LOG}"
  exit 1
fi
