#!/usr/bin/env bash
# migrate-sessions.sh — Migrate sessions and messages from OpenCode to Gewu
set -euo pipefail

# shellcheck source=./migration-config.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/migration-config.sh"

DRY_RUN="${DRY_RUN:-false}"
SESSION_COUNT=0
MESSAGE_COUNT=0
PROJECT_COUNT=0
SKIPPED_COUNT=0
ERROR_COUNT=0

log_info "=== Starting session migration (dry-run: ${DRY_RUN}) ==="

# ── Verify source tables ──────────────────────────────────────────────────────
for tbl in sessions session_message session_part; do
  if ! mysql_opencode "DESCRIBE ${tbl};" &>/dev/null; then
    log_warn "Source table opencode.${tbl} does not exist — will be skipped"
  fi
done

# ── Phase 1: Create project entries from sessions ─────────────────────────────
# Gewu groups sessions into projects. We create one project per source user.
log_info "Phase 1/3: Creating project entries"

if [[ "${DRY_RUN}" == "false" ]]; then
  mysql_gewu "
    CREATE TABLE IF NOT EXISTS project (
      id          VARCHAR(26)  NOT NULL PRIMARY KEY COMMENT 'ULID',
      name        VARCHAR(255) NOT NULL,
      user_id     VARCHAR(26)  NOT NULL,
      description TEXT         DEFAULT NULL,
      created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
      updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
      INDEX idx_project_user (user_id),
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
  " 2>/dev/null || true

  mysql_gewu "
    CREATE TABLE IF NOT EXISTS session (
      id          VARCHAR(26)  NOT NULL PRIMARY KEY COMMENT 'ULID',
      project_id  VARCHAR(26)  NOT NULL,
      title       VARCHAR(512) DEFAULT NULL,
      status      ENUM('active','archived') NOT NULL DEFAULT 'active',
      created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
      updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
      INDEX idx_session_project (project_id),
      FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
  " 2>/dev/null || true
fi

# Read source sessions
OFFSET=0
while true; do
  BATCH_DATA=$(mysql_opencode "
    SELECT s.id, s.title, s.user_id, s.created_at, s.updated_at
    FROM sessions s
    LIMIT ${BATCH_SIZE} OFFSET ${OFFSET};
  " 2>/dev/null || echo "")

  [[ -z "${BATCH_DATA}" ]] && break

  while IFS=$'\t' read -r SESSION_ID TITLE USER_ID CREATED_AT UPDATED_AT; do
    [[ -z "${SESSION_ID}" ]] && continue

    # Map user_id to Gewu ULID
    USER_ULID=$("${TRANSFORM_ULID}" to-ulid "${USER_ID}" 2>/dev/null || echo "")
    [[ -z "${USER_ULID}" ]] && { log_error "No ULID mapping for user ${USER_ID}"; ERROR_COUNT=$((ERROR_COUNT + 1)); continue; }

    # Deterministic project_id from user_id
    PROJECT_ULID=$("${TRANSFORM_ULID}" to-ulid "project:${USER_ID}" 2>/dev/null || echo "")
    SESSION_ULID=$("${TRANSFORM_ULID}" to-ulid "${SESSION_ID}" 2>/dev/null || echo "")
    [[ -z "${PROJECT_ULID}" || -z "${SESSION_ULID}" ]] && { log_error "ULID generation failed for session ${SESSION_ID}"; ERROR_COUNT=$((ERROR_COUNT + 1)); continue; }

    log_debug "Session ${SESSION_ID} → project ${PROJECT_ULID} / session ${SESSION_ULID}"

    if [[ "${DRY_RUN}" == "true" ]]; then
      echo "[DRY-RUN] INSERT project id='${PROJECT_ULID}' user_id='${USER_ULID}'"
      echo "[DRY-RUN] INSERT session id='${SESSION_ULID}' project_id='${PROJECT_ULID}' title='${TITLE}'"
      PROJECT_COUNT=$((PROJECT_COUNT + 1))
      SESSION_COUNT=$((SESSION_COUNT + 1))
      continue
    fi

    # INSERT IGNORE project (one per user)
    mysql_gewu "
      INSERT IGNORE INTO project (id, name, user_id, created_at, updated_at)
      VALUES (
        '${PROJECT_ULID}',
        'User ${USER_ULID} Project',
        '${USER_ULID}',
        NOW(3),
        NOW(3)
      );
    " 2>/dev/null || true

    # INSERT IGNORE session
    mysql_gewu "
      INSERT IGNORE INTO session (id, project_id, title, created_at, updated_at)
      VALUES (
        '${SESSION_ULID}',
        '${PROJECT_ULID}',
        $(mysql_quote "${TITLE:-Untitled Session}"),
        $(mysql_datetime "${CREATED_AT}"),
        $(mysql_datetime "${UPDATED_AT}")
      );
    " 2>/dev/null || true

    SESSION_COUNT=$((SESSION_COUNT + 1))
  done <<< "${BATCH_DATA}"

  OFFSET=$((OFFSET + BATCH_SIZE))
done

# ── Phase 2: Migrate session messages ────────────────────────────────────────
log_info "Phase 2/3: Migrating session messages"

if [[ "${DRY_RUN}" == "false" ]]; then
  mysql_gewu "
    CREATE TABLE IF NOT EXISTS session_message (
      id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
      session_id  VARCHAR(26)  NOT NULL,
      role        ENUM('user','assistant','system') NOT NULL,
      content     MEDIUMTEXT   NOT NULL,
      part_type   VARCHAR(32)  DEFAULT NULL COMMENT 'V1 part type mapping',
      part_meta   JSON         DEFAULT NULL,
      sequence    INT          NOT NULL DEFAULT 0,
      created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
      INDEX idx_msg_session (session_id),
      FOREIGN KEY (session_id) REFERENCES session(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
  " 2>/dev/null || true
fi

# Merge V1 message + part tables into session_message
OFFSET=0
while true; do
  BATCH_DATA=$(mysql_opencode "
    SELECT
      sm.id           AS msg_id,
      sm.session_id   AS session_id,
      sm.role         AS role,
      sm.created_at   AS created_at,
      sp.part_type    AS part_type,
      sp.content      AS content,
      sp.metadata     AS part_meta,
      sp.sequence     AS sequence
    FROM session_message sm
    LEFT JOIN session_part sp ON sp.message_id = sm.id
    ORDER BY sm.id, sp.sequence
    LIMIT ${BATCH_SIZE} OFFSET ${OFFSET};
  " 2>/dev/null || echo "")

  [[ -z "${BATCH_DATA}" ]] && break

  while IFS=$'\t' read -r MSG_ID SESSION_ID ROLE CREATED_AT PART_TYPE CONTENT PART_META SEQ; do
    [[ -z "${MSG_ID}" ]] && continue

    SESSION_ULID=$("${TRANSFORM_ULID}" to-ulid "${SESSION_ID}" 2>/dev/null || echo "")
    [[ -z "${SESSION_ULID}" ]] && { log_error "No ULID mapping for session ${SESSION_ID}"; ERROR_COUNT=$((ERROR_COUNT + 1)); continue; }

    if [[ "${DRY_RUN}" == "true" ]]; then
      echo "[DRY-RUN] INSERT session_message session_id='${SESSION_ULID}' role='${ROLE}' seq='${SEQ}'"
      MESSAGE_COUNT=$((MESSAGE_COUNT + 1))
      continue
    fi

    CONTENT_ESCAPED=$(mysql_quote "${CONTENT:-}")
    META_ESCAPED=$(mysql_quote "${PART_META:-}")

    mysql_gewu "
      INSERT INTO session_message (session_id, role, content, part_type, part_meta, sequence, created_at)
      VALUES (
        '${SESSION_ULID}',
        $(mysql_quote "${ROLE}"),
        ${CONTENT_ESCAPED},
        $(mysql_quote "${PART_TYPE:-}"),
        ${META_ESCAPED},
        ${SEQ:-0},
        $(mysql_datetime "${CREATED_AT}")
      );
    " 2>/dev/null || true

    MESSAGE_COUNT=$((MESSAGE_COUNT + 1))
  done <<< "${BATCH_DATA}"

  OFFSET=$((OFFSET + BATCH_SIZE))
done

# ── Phase 3: Link project_id ↔ session mapping (integrity pass) ──────────────
log_info "Phase 3/3: Verifying project-session integrity"
if [[ "${DRY_RUN}" == "false" ]]; then
  ORPHANS=$(mysql_gewu "
    SELECT COUNT(*) FROM session s
    LEFT JOIN project p ON p.id = s.project_id
    WHERE p.id IS NULL;
  " 2>/dev/null || echo "0")

  if [[ "${ORPHANS}" -gt 0 ]]; then
    log_warn "Found ${ORPHANS} orphan sessions without a project"
    ERROR_COUNT=$((ERROR_COUNT + ORPHANS))
  fi

  write_checkpoint "sessions" "done:${SESSION_COUNT}:${MESSAGE_COUNT}:${PROJECT_COUNT}:${SKIPPED_COUNT}:${ERROR_COUNT}"
fi

log_info "Session migration complete: ${SESSION_COUNT} sessions, ${MESSAGE_COUNT} messages, ${ERROR_COUNT} errors"

# ── Helpers ───────────────────────────────────────────────────────────────────
mysql_quote() {
  local s="$1"
  [[ -z "${s}" ]] && { echo "NULL"; return; }
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
