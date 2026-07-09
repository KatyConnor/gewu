#!/usr/bin/env bash
# validate-all.sh — Comprehensive data validation runner for OpenCode → Gewu migration
# Runs count, FK, ULID, and integrity checks, then generates an HTML report.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./migration-config.sh
source "${SCRIPT_DIR}/migration-config.sh"

OVERALL_STATUS=0
RESULTS_FILE="$(mktemp /tmp/validate-all-results.XXXXXX)"
trap 'rm -f "${RESULTS_FILE}"' EXIT

# Redirect all output through the logging helpers but also capture raw lines
exec 3>&1

record() {
  local status="$1"  # PASS | FAIL | WARN
  local name="$2"
  local msg="$3"
  echo "[${status}] ${name}: ${msg}" | tee -a "${RESULTS_FILE}" >&3
}

rejected() {
  echo "[REJECTED] $*" | tee -a "${RESULTS_FILE}" >&3
}

check_mysql || { log_error "MySQL connection required"; exit 1; }

# ── 1. Count validation ────────────────────────────────────────────────────────
validate_counts() {
  local all_pass=true
  log_info "Validating record counts..."

  local pairs=(
    "users:users"
    "sessions:sessions"
    "session_messages:messages"
    "agents:agents"
  )

  for pair in "${pairs[@]}"; do
    local source_table="${pair%%:*}"
    local target_table="${pair##*:}"
    local src_count target_count

    src_count="$(mysql_opencode "SELECT COUNT(*) FROM opencode_${source_table}" 2>/dev/null || echo "ERROR")"
    target_count="$(mysql_gewu "SELECT COUNT(*) FROM gewu.${target_table}" 2>/dev/null || echo "ERROR")"

    if [[ "${src_count}" == "ERROR" ]] || [[ "${target_count}" == "ERROR" ]]; then
      record "FAIL" "validate-counts" "${source_table}: source=${src_count}, target=${target_count} (query error)"
      all_pass=false
    elif [[ "${src_count}" -eq "${target_count}" ]]; then
      record "PASS" "validate-counts" "${source_table}: ${src_count} == ${target_count}"
    else
      record "FAIL" "validate-counts" "${source_table}: ${src_count} != ${target_count} (mismatch)"
      all_pass=false
    fi
  done

  ${all_pass}
  return $?
}

# ── 2. Foreign key validation ──────────────────────────────────────────────────
validate_fks() {
  local all_pass=true
  log_info "Validating foreign key relationships..."

  # session.user_id → gewu.users.id
  local orphans
  orphans="$(mysql_gewu "
    SELECT COUNT(*) FROM sessions s
    LEFT JOIN users u ON s.user_id = u.id
    WHERE u.id IS NULL
  " 2>/dev/null || echo "ERROR")"

  if [[ "${orphans}" == "ERROR" ]]; then
    record "FAIL" "validate-fks" "sessions.user_id query failed"
    all_pass=false
  elif [[ "${orphans}" -eq 0 ]]; then
    record "PASS" "validate-fks" "sessions.user_id → users.id: OK"
  else
    record "FAIL" "validate-fks" "sessions.user_id → users.id: ${orphans} orphaned session(s)"
    all_pass=false
  fi

  # message.session_id → sessions.id (gewu uses session_messages for messages)
  orphans="$(mysql_gewu "
    SELECT COUNT(*) FROM session_messages sm
    LEFT JOIN sessions s ON sm.session_id = s.id
    WHERE s.id IS NULL
  " 2>/dev/null || echo "ERROR")"

  if [[ "${orphans}" == "ERROR" ]]; then
    record "FAIL" "validate-fks" "session_messages.session_id query failed"
    all_pass=false
  elif [[ "${orphans}" -eq 0 ]]; then
    record "PASS" "validate-fks" "session_messages.session_id → sessions.id: OK"
  else
    record "FAIL" "validate-fks" "session_messages.session_id → sessions.id: ${orphans} orphaned message(s)"
    all_pass=false
  fi

  # agents.created_by → users.id
  orphans="$(mysql_gewu "
    SELECT COUNT(*) FROM agents a
    LEFT JOIN users u ON a.created_by = u.id
    WHERE a.created_by IS NOT NULL AND u.id IS NULL
  " 2>/dev/null || echo "ERROR")"

  if [[ "${orphans}" == "ERROR" ]]; then
    record "FAIL" "validate-fks" "agents.created_by query failed"
    all_pass=false
  elif [[ "${orphans}" -eq 0 ]]; then
    record "PASS" "validate-fks" "agents.created_by → users.id: OK"
  else
    record "FAIL" "validate-fks" "agents.created_by → users.id: ${orphans} orphaned agent(s)"
    all_pass=false
  fi

  ${all_pass}
  return $?
}

# ── 3. ULID validation ─────────────────────────────────────────────────────────
validate_ulids() {
  local all_pass=true
  log_info "Validating ULID fields..."
  local ulid_re='^[0-9A-HJKMNP-TV-Z]{26}$'

  local tables_columns=(
    "users:id"
    "sessions:id"
    "session_messages:id"
    "agents:id"
  )

  for entry in "${tables_columns[@]}"; do
    local table="${entry%%:*}"
    local column="${entry##*:}"
    local bad_count

    bad_count="$(mysql_gewu "
      SELECT COUNT(*) FROM ${table}
      WHERE ${column} IS NOT NULL
        AND ${column} NOT REGEXP '${ulid_re}'
    " 2>/dev/null || echo "ERROR")"

    if [[ "${bad_count}" == "ERROR" ]]; then
      record "FAIL" "validate-ulids" "${table}.${column} query failed"
      all_pass=false
    elif [[ "${bad_count}" -eq 0 ]]; then
      record "PASS" "validate-ulids" "${table}.${column}: all valid"
    else
      record "FAIL" "validate-ulids" "${table}.${column}: ${bad_count} invalid ULID(s)"
      all_pass=false
      # Show invalid rows
      mysql_gewu "
        SELECT id FROM ${table}
        WHERE ${column} IS NOT NULL
          AND ${column} NOT REGEXP '${ulid_re}'
        LIMIT 20
      " 2>/dev/null | while IFS= read -r bad_id; do
        [[ -n "${bad_id}" ]] && rejected "Invalid ULID in ${table}.${column}: ${bad_id}"
      done
    fi
  done

  ${all_pass}
  return $?
}

# ── 4. Integrity validation ────────────────────────────────────────────────────
validate_integrity() {
  local all_pass=true
  log_info "Validating data integrity..."

  # Check for NULL required fields
  local required_checks=(
    "users:email:email IS NULL"
    "sessions:title:title IS NULL OR title = ''"
    "session_messages:content:content IS NULL"
    "agents:name:name IS NULL OR name = ''"
  )

  for check in "${required_checks[@]}"; do
    local table="${check%%:*}"
    local field="${check#*:}"
    field="${field%%:*}"
    local condition="${check##*:}"
    local null_count

    null_count="$(mysql_gewu "
      SELECT COUNT(*) FROM ${table} WHERE ${condition}
    " 2>/dev/null || echo "ERROR")"

    if [[ "${null_count}" == "ERROR" ]]; then
      record "FAIL" "validate-integrity" "${table}.${field} query failed"
      all_pass=false
    elif [[ "${null_count}" -eq 0 ]]; then
      record "PASS" "validate-integrity" "${table}.${field}: no nulls/empties"
    else
      record "WARN" "validate-integrity" "${table}.${field}: ${null_count} null/empty value(s)"
    fi
  done

  # Check for duplicate emails in users
  local dup_count
  dup_count="$(mysql_gewu "
    SELECT COUNT(*) FROM (
      SELECT email FROM users WHERE email IS NOT NULL GROUP BY email HAVING COUNT(*) > 1
    ) dups
  " 2>/dev/null || echo "ERROR")"

  if [[ "${dup_count}" == "ERROR" ]]; then
    record "FAIL" "validate-integrity" "duplicate email query failed"
    all_pass=false
  elif [[ "${dup_count}" -eq 0 ]]; then
    record "PASS" "validate-integrity" "users.email: no duplicates"
  else
    record "FAIL" "validate-integrity" "users.email: ${dup_count} duplicate email(s)"
    all_pass=false
  fi

  # Check timestamp ordering (message.created_at >= session.created_at)
  local bad_ts
  bad_ts="$(mysql_gewu "
    SELECT COUNT(*) FROM session_messages sm
    JOIN sessions s ON sm.session_id = s.id
    WHERE sm.created_at < s.created_at
  " 2>/dev/null || echo "ERROR")"

  if [[ "${bad_ts}" == "ERROR" ]]; then
    record "WARN" "validate-integrity" "timestamp ordering query failed (may not exist)"
  elif [[ "${bad_ts}" -eq 0 ]]; then
    record "PASS" "validate-integrity" "message timestamps >= session timestamps"
  else
    record "FAIL" "validate-integrity" "${bad_ts} message(s) with timestamp before parent session"
    all_pass=false
  fi

  ${all_pass}
  return $?
}

# ── Main validation run ────────────────────────────────────────────────────────
START_TIME="$(date +%s)"

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  OpenCode → Gewu Migration Validation"
echo "  Started: $(date)"
echo "═══════════════════════════════════════════════════════════════"
echo ""

# Run all validations, collect pass/fail
validation_passed=true

validate_counts || validation_passed=false
validate_fks || validation_passed=false
validate_ulids || validation_passed=false
validate_integrity || validation_passed=false

END_TIME="$(date +%s)"
DURATION=$((END_TIME - START_TIME))

echo ""
echo "───────────────────────────────────────────────────────────────"
echo "  Validation Summary"
echo "───────────────────────────────────────────────────────────────"

pass_count="$(grep -c '\[PASS\]' "${RESULTS_FILE}" || true)"
fail_count="$(grep -c '\[FAIL\]' "${RESULTS_FILE}" || true)"
warn_count="$(grep -c '\[WARN\]' "${RESULTS_FILE}" || true)"

echo "  Passed: ${pass_count}"
echo "  Failed: ${fail_count}"
echo "  Warnings: ${warn_count}"
echo "  Duration: ${DURATION}s"
echo ""

if ${validation_passed}; then
  record "PASS" "OVERALL" "All validations passed (${DURATION}s)"
  echo "  OVERALL: PASS"
  echo ""
  OVERALL_STATUS=0
else
  record "FAIL" "OVERALL" "Some validations failed (${DURATION}s)"
  echo "  OVERALL: FAIL"
  echo ""
  OVERALL_STATUS=1
fi

# Call report generator with results
"${SCRIPT_DIR}/generate-validation-report.sh" --input "${RESULTS_FILE}" 2>&1 || {
  log_warn "Report generation failed (non-fatal)"
}

exit ${OVERALL_STATUS}
