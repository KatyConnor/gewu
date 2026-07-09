#!/usr/bin/env bash
# migration-config.sh — shared configuration sourced by all migration scripts
set -euo pipefail

# ── MySQL connection parameters ──────────────────────────────────────────────
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASS="${MYSQL_PASS:-}"
MYSQL_OPTS="-h ${MYSQL_HOST} -P ${MYSQL_PORT} -u ${MYSQL_USER}"
[[ -n "${MYSQL_PASS}" ]] && MYSQL_OPTS="${MYSQL_OPTS} -p${MYSQL_PASS}"

OPENCODE_DB="${OPENCODE_DB:-opencode}"
GEWU_DB="${GEWU_DB:-gewu}"

# ── Batch settings ────────────────────────────────────────────────────────────
BATCH_SIZE="${BATCH_SIZE:-1000}"

# ── Logging ───────────────────────────────────────────────────────────────────
LOG_LEVEL="${LOG_LEVEL:-INFO}"   # DEBUG | INFO | WARN | ERROR
LOG_FILE="${LOG_FILE:-}"

# ── Timeouts (seconds) ────────────────────────────────────────────────────────
MYSQL_TIMEOUT="${MYSQL_TIMEOUT:-30}"
SCRIPT_TIMEOUT="${SCRIPT_TIMEOUT:-3600}"

# ── Paths ─────────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CHECKPOINT_DIR="${CHECKPOINT_DIR:-${SCRIPT_DIR}/.checkpoints}"
TRANSFORM_ULID="${SCRIPT_DIR}/transform-ulid.py"

# ── Logging helpers ───────────────────────────────────────────────────────────
_log() { local level="$1" msg="$2"; [[ -n "${LOG_FILE}" ]] && echo "[$(date '+%Y-%m-%d %H:%M:%S')] [${level}] ${msg}" >> "${LOG_FILE}"; echo "[${level}] ${msg}" >&2; }
log_debug() { [[ "${LOG_LEVEL}" =~ ^(DEBUG)$ ]] && _log "DEBUG" "$*"; }
log_info()  { [[ "${LOG_LEVEL}" =~ ^(DEBUG|INFO)$ ]] && _log "INFO"  "$*"; }
log_warn()  { [[ "${LOG_LEVEL}" =~ ^(DEBUG|INFO|WARN)$ ]] && _log "WARN"  "$*"; }
log_error() { _log "ERROR" "$*"; }

# ── Checkpoint helpers ────────────────────────────────────────────────────────
_checkpoint_dir() { mkdir -p "${CHECKPOINT_DIR}"; }
write_checkpoint() {
  _checkpoint_dir
  echo "$2" > "${CHECKPOINT_DIR}/$1.checkpoint"
}
read_checkpoint() {
  local f="${CHECKPOINT_DIR}/$1.checkpoint"
  [[ -f "${f}" ]] && cat "${f}" || echo ""
}
clear_checkpoint() {
  rm -f "${CHECKPOINT_DIR}/$1.checkpoint"
}

# ── MySQL helpers ─────────────────────────────────────────────────────────────
mysql_exec() { mysql ${MYSQL_OPTS} --connect-timeout="${MYSQL_TIMEOUT}" "$@"; }
mysql_opencode() { mysql_exec -N -B "${OPENCODE_DB}" -e "$1"; }
mysql_gewu()    { mysql_exec -N -B "${GEWU_DB}" -e "$1"; }

# ── Prerequisite checks ───────────────────────────────────────────────────────
check_mysql() {
  if ! command -v mysql &>/dev/null; then
    log_error "MySQL client not found. Install mysql-client package."
    return 1
  fi
  if ! mysql_exec -e "SELECT 1" &>/dev/null; then
    log_error "Cannot connect to MySQL at ${MYSQL_HOST}:${MYSQL_PORT}"
    return 1
  fi
  log_info "MySQL connection OK"
}

check_disk_space() {
  local required_kb="$(( ${1:-1048576} ))"  # default 1 GB in KB
  local avail_kb
  avail_kb="$(df "${SCRIPT_DIR}" | awk 'NR==2 {print $4}')"
  if [[ "${avail_kb}" -lt "${required_kb}" ]]; then
    log_error "Insufficient disk space: ${avail_kb} KB available, need ${required_kb} KB"
    return 1
  fi
  log_info "Disk space OK (${avail_kb} KB available)"
}

check_backup_exists() {
  local label="${1:-pre-migration}"
  local backup_dir="${PROJECT_ROOT}/backups/${label}"
  if [[ ! -d "${backup_dir}" ]]; then
    log_warn "Backup directory ${backup_dir} not found. Run backup first or create it."
    return 1
  fi
  log_info "Backup found at ${backup_dir}"
}
