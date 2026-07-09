#!/usr/bin/env bash
# migrate-agents.sh — Migrate agents, tools, and plugins from OpenCode to Gewu
set -euo pipefail

# shellcheck source=./migration-config.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/migration-config.sh"

DRY_RUN="${DRY_RUN:-false}"
AGENT_COUNT=0
TOOL_COUNT=0
SKIPPED_COUNT=0
ERROR_COUNT=0

log_info "=== Starting agent migration (dry-run: ${DRY_RUN}) ==="

# ── Verify source tables ──────────────────────────────────────────────────────
for tbl in agents agent_tools agent_plugins; do
  if ! mysql_opencode "DESCRIBE ${tbl};" &>/dev/null; then
    log_warn "Source table opencode.${tbl} does not exist — will be skipped"
  fi
done

# ── Phase 1: Migrate agents ───────────────────────────────────────────────────
log_info "Phase 1/2: Migrating agents"

if [[ "${DRY_RUN}" == "false" ]]; then
  mysql_gewu "
    CREATE TABLE IF NOT EXISTS agent (
      id          VARCHAR(26)  NOT NULL PRIMARY KEY COMMENT 'ULID',
      user_id     VARCHAR(26)  NOT NULL,
      name        VARCHAR(255) NOT NULL,
      description TEXT         DEFAULT NULL,
      model       VARCHAR(128) DEFAULT NULL,
      system_prompt TEXT       DEFAULT NULL,
      temperature DECIMAL(4,2) DEFAULT NULL,
      is_active   TINYINT(1)   NOT NULL DEFAULT 1,
      created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
      updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
      INDEX idx_agent_user (user_id),
      INDEX idx_agent_active (is_active),
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
  " 2>/dev/null || true

  mysql_gewu "
    CREATE TABLE IF NOT EXISTS agent_tool (
      id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
      agent_id    VARCHAR(26)  NOT NULL,
      tool_name   VARCHAR(255) NOT NULL,
      tool_type   ENUM('builtin','plugin','custom') NOT NULL DEFAULT 'builtin',
      config      JSON         DEFAULT NULL,
      enabled     TINYINT(1)   NOT NULL DEFAULT 1,
      created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
      INDEX idx_agent_tool_agent (agent_id),
      FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
  " 2>/dev/null || true
fi

OFFSET=0
while true; do
  BATCH_DATA=$(mysql_opencode "
    SELECT a.id, a.user_id, a.name, a.description, a.model,
           a.system_prompt, a.temperature, a.is_active,
           a.created_at, a.updated_at
    FROM agents a
    LIMIT ${BATCH_SIZE} OFFSET ${OFFSET};
  " 2>/dev/null || echo "")

  [[ -z "${BATCH_DATA}" ]] && break

  while IFS=$'\t' read -r AGENT_ID USER_ID NAME DESCRIPTION MODEL SYSTEM_PROMPT TEMPERATURE IS_ACTIVE CREATED_AT UPDATED_AT; do
    [[ -z "${AGENT_ID}" ]] && continue

    AGENT_ULID=$("${TRANSFORM_ULID}" to-ulid "${AGENT_ID}" 2>/dev/null || echo "")
    USER_ULID=$("${TRANSFORM_ULID}" to-ulid "${USER_ID}" 2>/dev/null || echo "")

    if [[ -z "${AGENT_ULID}" || -z "${USER_ULID}" ]]; then
      log_error "ULID generation failed for agent ${AGENT_ID}"
      ERROR_COUNT=$((ERROR_COUNT + 1))
      continue
    fi

    log_debug "Agent ${AGENT_ID} → ${AGENT_ULID} (user ${USER_ULID})"

    if [[ "${DRY_RUN}" == "true" ]]; then
      echo "[DRY-RUN] INSERT agent id='${AGENT_ULID}' user_id='${USER_ULID}' name='${NAME}'"
      AGENT_COUNT=$((AGENT_COUNT + 1))
      continue
    fi

    mysql_gewu "
      INSERT IGNORE INTO agent (id, user_id, name, description, model, system_prompt, temperature, is_active, created_at, updated_at)
      VALUES (
        '${AGENT_ULID}',
        '${USER_ULID}',
        $(mysql_quote "${NAME}"),
        $(mysql_quote "${DESCRIPTION:-}"),
        $(mysql_quote "${MODEL:-}"),
        $(mysql_quote "${SYSTEM_PROMPT:-}"),
        ${TEMPERATURE:-NULL},
        ${IS_ACTIVE:-1},
        $(mysql_datetime "${CREATED_AT}"),
        $(mysql_datetime "${UPDATED_AT}")
      );
    " 2>/dev/null || { log_warn "Failed to insert agent ${AGENT_ULID}"; ERROR_COUNT=$((ERROR_COUNT + 1)); continue; }

    AGENT_COUNT=$((AGENT_COUNT + 1))

    # ── Phase 2: Migrate agent tools/plugins ──────────────────────────────────
    # Read tools for this agent
    TOOL_BATCH=$(mysql_opencode "
      SELECT tool_name, 'builtin' AS tool_type, config
      FROM agent_tools WHERE agent_id = '${AGENT_ID}'
      UNION ALL
      SELECT plugin_name AS tool_name, 'plugin' AS tool_type, config
      FROM agent_plugins WHERE agent_id = '${AGENT_ID}';
    " 2>/dev/null || echo "")

    if [[ -n "${TOOL_BATCH}" ]]; then
      while IFS=$'\t' read -r TOOL_NAME TOOL_TYPE TOOL_CONFIG; do
        [[ -z "${TOOL_NAME}" ]] && continue

        if [[ "${DRY_RUN}" == "true" ]]; then
          echo "[DRY-RUN] INSERT agent_tool agent_id='${AGENT_ULID}' tool='${TOOL_NAME}' type='${TOOL_TYPE}'"
          TOOL_COUNT=$((TOOL_COUNT + 1))
          continue
        fi

        mysql_gewu "
          INSERT IGNORE INTO agent_tool (agent_id, tool_name, tool_type, config)
          VALUES (
            '${AGENT_ULID}',
            $(mysql_quote "${TOOL_NAME}"),
            $(mysql_quote "${TOOL_TYPE}"),
            $(mysql_quote "${TOOL_CONFIG:-}")
          );
        " 2>/dev/null || true

        TOOL_COUNT=$((TOOL_COUNT + 1))
      done <<< "${TOOL_BATCH}"
    fi
  done <<< "${BATCH_DATA}"

  OFFSET=$((OFFSET + BATCH_SIZE))
done

# ── Write checkpoint ──────────────────────────────────────────────────────────
if [[ "${DRY_RUN}" == "false" ]]; then
  write_checkpoint "agents" "done:${AGENT_COUNT}:${TOOL_COUNT}:${SKIPPED_COUNT}:${ERROR_COUNT}"
fi

log_info "Agent migration complete: ${AGENT_COUNT} agents, ${TOOL_COUNT} tools, ${ERROR_COUNT} errors"

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
