#!/usr/bin/env bash
# migrate-users.sh — Migrate users from OpenCode to Gewu
set -euo pipefail

# shellcheck source=./migration-config.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/migration-config.sh"

DRY_RUN="${DRY_RUN:-false}"
MIGRATED_COUNT=0
SKIPPED_COUNT=0
ERROR_COUNT=0

log_info "=== Starting user migration (dry-run: ${DRY_RUN}) ==="

# ── Verify source table exists ────────────────────────────────────────────────
if ! mysql_opencode "DESCRIBE users;" &>/dev/null; then
  log_error "Source table opencode.users does not exist or is inaccessible"
  exit 1
fi

# ── Fetch users in batches ────────────────────────────────────────────────────
OFFSET=0
TOTAL=$(mysql_opencode "SELECT COUNT(*) FROM users;" 2>/dev/null || echo "0")
log_info "Found ${TOTAL} users to migrate"

if [[ "${TOTAL}" -eq 0 ]]; then
  log_info "No users to migrate"
  write_checkpoint "users" "done"
  exit 0
fi

# ── Create target table if not exists ─────────────────────────────────────────
if [[ "${DRY_RUN}" == "false" ]]; then
  mysql_gewu "
    CREATE TABLE IF NOT EXISTS users (
      id            VARCHAR(26)  NOT NULL PRIMARY KEY COMMENT 'ULID',
      username      VARCHAR(255) NOT NULL UNIQUE,
      nickname      VARCHAR(255) DEFAULT NULL,
      email         VARCHAR(255) DEFAULT NULL UNIQUE,
      password_hash VARCHAR(255) NOT NULL,
      sm3_hash      VARCHAR(64)  DEFAULT NULL COMMENT 'Reconciliation hash',
      created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
      updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
      INDEX idx_users_username (username),
      INDEX idx_users_email (email)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
  " 2>/dev/null || true
fi

while true; do
  # Read one batch of users
  BATCH_DATA=$(mysql_opencode "
    SELECT id, username, display_name, email, password_hash, created_at, updated_at
    FROM users
    LIMIT ${BATCH_SIZE} OFFSET ${OFFSET};
  " 2>/dev/null || echo "")

  [[ -z "${BATCH_DATA}" ]] && break

  while IFS=$'\t' read -r ID USERNAME DISPLAY_NAME EMAIL PASSWORD_HASH CREATED_AT UPDATED_AT; do
    [[ -z "${ID}" ]] && continue

    # Generate ULID and SM3 hash
    ULID=$("${TRANSFORM_ULID}" to-ulid "${ID}" 2>/dev/null || echo "")
    if [[ -z "${ULID}" ]]; then
      log_error "Failed to generate ULID for user ${ID}"
      ERROR_COUNT=$((ERROR_COUNT + 1))
      continue
    fi

    SM3=$("${TRANSFORM_ULID}" sm3 "${ULID}:${USERNAME}" 2>/dev/null || echo "")

    log_debug "Mapping user ${ID} → ${ULID} (${USERNAME})"

    if [[ "${DRY_RUN}" == "true" ]]; then
      echo "[DRY-RUN] INSERT INTO gewu.users (id='${ULID}', username='${USERNAME}', nickname='${DISPLAY_NAME}', email='${EMAIL}')"
      MIGRATED_COUNT=$((MIGRATED_COUNT + 1))
      continue
    fi

    # MERGE / INSERT IGNORE — skip if ULID or username already exists
    INSERT_RESULT=$(mysql_gewu "
      INSERT IGNORE INTO users (id, username, nickname, email, password_hash, sm3_hash, created_at, updated_at)
      VALUES (
        '${ULID}',
        $(mysql_quote "${USERNAME}"),
        $(mysql_quote "${DISPLAY_NAME:-}"),
        $(mysql_quote "${EMAIL:-}"),
        '${PASSWORD_HASH}',
        '${SM3}',
        $(mysql_datetime "${CREATED_AT}"),
        $(mysql_datetime "${UPDATED_AT}")
      );
    " 2>&1 || echo "ERROR:$?")

    if [[ "${INSERT_RESULT}" == "ERROR:"* ]]; then
      log_warn "Failed to insert user ${ULID} (${USERNAME}): ${INSERT_RESULT}"
      ERROR_COUNT=$((ERROR_COUNT + 1))
    elif [[ -z "${INSERT_RESULT}" || "${INSERT_RESULT}" == "0" ]]; then
      MIGRATED_COUNT=$((MIGRATED_COUNT + 1))
    else
      SKIPPED_COUNT=$((SKIPPED_COUNT + 1))
    fi
  done <<< "${BATCH_DATA}"

  OFFSET=$((OFFSET + BATCH_SIZE))
done

log_info "User migration complete: ${MIGRATED_COUNT} migrated, ${SKIPPED_COUNT} skipped, ${ERROR_COUNT} errors"

# ── Write checkpoint ──────────────────────────────────────────────────────────
if [[ "${DRY_RUN}" == "false" ]]; then
  write_checkpoint "users" "done:${MIGRATED_COUNT}:${SKIPPED_COUNT}:${ERROR_COUNT}"
fi

# ── Helper: safely quote string for MySQL ─────────────────────────────────────
mysql_quote() {
  local s="$1"
  [[ -z "${s}" ]] && { echo "NULL"; return; }
  # Escape single quotes and backslashes
  s="${s//\\/\\\\}"
  s="${s//\'/\\\'}"
  echo "'${s}'"
}

mysql_datetime() {
  local ts="$1"
  [[ -z "${ts}" || "${ts}" == "NULL" || "${ts}" == "0000-00-00"* ]] && { echo "NULL"; return; }
  echo "'${ts}'"
}

exit 0
