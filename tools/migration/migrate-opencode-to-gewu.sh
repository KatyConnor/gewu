#!/usr/bin/env bash
# migrate-opencode-to-gewu.sh — Main entry point for OpenCode → Gewu migration
set -euo pipefail

# shellcheck source=./migration-config.sh
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/migration-config.sh"

# ── CLI flag defaults ─────────────────────────────────────────────────────────
DRY_RUN=false
RESUME=false
ROLLBACK=false

# ── Parse arguments ───────────────────────────────────────────────────────────
usage() {
  cat >&2 <<EOF
Usage: $0 [--dry-run] [--resume] [--rollback]

Migrate data from OpenCode database to Gewu database.

Options:
  --dry-run   Show what would be done without making changes
  --resume    Resume from last successful checkpoint
  --rollback  Run rollback procedure instead of migration

Exit codes:
  0   Success
  1   Prerequisite failure
  2   Partial migration (some phases failed)
  3   Fatal error
EOF
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)  DRY_RUN=true; shift ;;
    --resume)   RESUME=true; shift ;;
    --rollback) ROLLBACK=true; shift ;;
    --help|-h)  usage ;;
    *)          echo "Unknown option: $1" >&2; usage ;;
  esac
done

# ── Setup logging ─────────────────────────────────────────────────────────────
TIMESTAMP=$(date '+%Y%m%d-%H%M%S')
LOG_FILE="${LOG_FILE:-${SCRIPT_DIR}/migration-${TIMESTAMP}.log}"
export LOG_FILE
log_info "Migration started at $(date)"
log_info "Args: dry-run=${DRY_RUN} resume=${RESUME} rollback=${ROLLBACK}"
log_info "Log file: ${LOG_FILE}"

# ── Rollback mode ─────────────────────────────────────────────────────────────
if [[ "${ROLLBACK}" == "true" ]]; then
  log_info "Rollback requested — calling rollback script"
  ROLLBACK_SCRIPT="${SCRIPT_DIR}/rollback-migration.sh"
  if [[ -x "${ROLLBACK_SCRIPT}" ]]; then
    exec "${ROLLBACK_SCRIPT}" "$@"
  else
    log_error "Rollback script not found or not executable: ${ROLLBACK_SCRIPT}"
    log_error "Manual rollback required. Restore from backup at ${PROJECT_ROOT}/backups/"
    exit 1
  fi
fi

# ── Prerequisite checks ───────────────────────────────────────────────────────
log_info "=== Checking prerequisites ==="
PREREQ_FAIL=false

check_mysql || PREREQ_FAIL=true
check_disk_space 1048576 || PREREQ_FAIL=true

# Backup check is advisory unless --resume is NOT set
if [[ "${RESUME}" == "false" ]]; then
  check_backup_exists "pre-migration" || PREREQ_FAIL=true
fi

if [[ "${PREREQ_FAIL}" == "true" ]]; then
  log_error "Prerequisite checks failed. Aborting."
  exit 1
fi

# Drop target tables on fresh run (not resume) so we start clean
if [[ "${RESUME}" == "false" && "${DRY_RUN}" == "false" ]]; then
  log_info "Clearing previous checkpoint data"
  rm -rf "${CHECKPOINT_DIR}"
fi

# ── Execute migration phases ──────────────────────────────────────────────────
OVERALL_STATUS=0

run_phase() {
  local phase_name="$1"
  local script_path="$2"
  local checkpoint_key="$3"

  log_info "=== Phase: ${phase_name} ==="

  if [[ "${RESUME}" == "true" ]]; then
    CHECK_VAL=$(read_checkpoint "${checkpoint_key}")
    if echo "${CHECK_VAL}" | grep -q "^done"; then
      log_info "Checkpoint '${checkpoint_key}' found (${CHECK_VAL}) — skipping"
      return 0
    fi
    log_info "No checkpoint for '${checkpoint_key}' — will execute"
  fi

  if [[ "${DRY_RUN}" == "true" ]]; then
    log_info "[DRY-RUN] Would execute: ${script_path} --dry-run"
    bash "${script_path}" --dry-run 2>&1 | tee -a "${LOG_FILE}"
    return 0
  fi

  # Execute phase script
  set +e
  DRY_RUN="${DRY_RUN}" bash "${script_path}" 2>&1 | tee -a "${LOG_FILE}"
  PHASE_EXIT="${PIPESTATUS[0]}"
  set -e

  if [[ "${PHASE_EXIT}" -ne 0 ]]; then
    log_error "Phase '${phase_name}' failed with exit code ${PHASE_EXIT}"
    return "${PHASE_EXIT}"
  fi

  log_info "Phase '${phase_name}' completed successfully"
  return 0
}

# Phase order: users → sessions → agents
PHASE_FAILED=false

run_phase "migrate-users"    "${SCRIPT_DIR}/migrate-users.sh"    "users"    || PHASE_FAILED=true
run_phase "migrate-sessions" "${SCRIPT_DIR}/migrate-sessions.sh" "sessions" || PHASE_FAILED=true
run_phase "migrate-agents"   "${SCRIPT_DIR}/migrate-agents.sh"   "agents"   || PHASE_FAILED=true

# ── Final summary ─────────────────────────────────────────────────────────────
log_info "=== Migration Summary ==="
echo "" >> "${LOG_FILE}"

for ck in users sessions agents; do
  VAL=$(read_checkpoint "${ck}" 2>/dev/null || echo "not-found")
  log_info "  ${ck}: ${VAL}"
done

if [[ "${DRY_RUN}" == "true" ]]; then
  log_info "DRY RUN completed — no data was modified"
  exit 0
fi

if [[ "${PHASE_FAILED}" == "true" ]]; then
  log_warn "Migration completed with one or more phase failures"
  log_warn "Run with --resume to retry failed phases"
  exit 2
fi

log_info "Migration completed successfully"
exit 0
