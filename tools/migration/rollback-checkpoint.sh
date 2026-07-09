#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/migration-config.sh"

LOG_FILE="${SCRIPT_DIR}/migration-$(date +%Y%m%d-%H%M%S).log"
CHECKPOINT_FILE="${SCRIPT_DIR}/migration-checkpoint.json"

log() {
    local msg="[$(date '+%Y-%m-%d %H:%M:%S')] [ROLLBACK-CHECKPOINT] $1"
    echo "$msg" | tee -a "$LOG_FILE"
}

usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Rollback to the last successful migration checkpoint.

Options:
    --dry-run       Preview rollback actions without executing
    --checkpoint N  Rollback to specific checkpoint number
    --force         Skip confirmation prompt
    -h, --help      Show this help

EOF
    exit 0
}

DRY_RUN=false
TARGET_CHECKPOINT=""
FORCE=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) DRY_RUN=true; shift ;;
        --checkpoint) TARGET_CHECKPOINT="$2"; shift 2 ;;
        --force) FORCE=true; shift ;;
        -h|--help) usage ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

if [[ ! -f "$CHECKPOINT_FILE" ]]; then
    log "ERROR: No checkpoint file found at $CHECKPOINT_FILE"
    log "Cannot determine rollback target. Use rollback-full.sh instead."
    exit 1
fi

LAST_CHECKPOINT=$(python3 -c "import json; data=json.load(open('$CHECKPOINT_FILE')); print(data.get('last_completed', 'none'))" 2>/dev/null || echo "none")

if [[ "$LAST_CHECKPOINT" == "none" ]]; then
    log "No completed checkpoints found. Nothing to rollback."
    exit 0
fi

if [[ -n "$TARGET_CHECKPOINT" ]]; then
    ROLLBACK_TO="$TARGET_CHECKPOINT"
else
    ROLLBACK_TO="$LAST_CHECKPOINT"
fi

log "Rollback target: checkpoint $ROLLBACK_TO"
log "Last completed: $LAST_CHECKPOINT"

PENDING_PHASES=$(python3 -c "
import json
data = json.load(open('$CHECKPOINT_FILE'))
completed = data.get('completed_phases', [])
all_phases = ['users', 'sessions', 'agents']
pending = [p for p in all_phases if p not in completed]
print(' '.join(pending))
" 2>/dev/null || echo "")

if [[ -z "$PENDING_PHASES" ]]; then
    log "No pending phases to rollback. All phases completed."
    exit 0
fi

log "Pending phases to rollback: $PENDING_PHASES"

if [[ "$DRY_RUN" == "true" ]]; then
    log "DRY-RUN: Would rollback the following:"
    for phase in $PENDING_PHASES; do
        case "$phase" in
            users)
                log "  - DELETE FROM gewu.users WHERE migrated_from_opencode = 1"
                ;;
            sessions)
                log "  - DELETE FROM gewu.session_message WHERE migrated_from_opencode = 1"
                log "  - DELETE FROM gewu.session WHERE migrated_from_opencode = 1"
                log "  - DELETE FROM gewu.project WHERE migrated_from_opencode = 1"
                ;;
            agents)
                log "  - DELETE FROM gewu.agent_tool WHERE migrated_from_opencode = 1"
                log "  - DELETE FROM gewu.agent WHERE migrated_from_opencode = 1"
                ;;
        esac
    done
    log "DRY-RUN: Checkpoint file would be updated"
    log "DRY-RUN complete. No changes made."
    exit 0
fi

if [[ "$FORCE" != "true" ]]; then
    echo "WARNING: This will delete migrated data for phases: $PENDING_PHASES"
    read -rp "Are you sure? (yes/no): " confirm
    if [[ "$confirm" != "yes" ]]; then
        log "Rollback cancelled by user."
        exit 0
    fi
fi

log "Starting checkpoint rollback..."

for phase in $PENDING_PHASES; do
    log "Rolling back phase: $phase"
    case "$phase" in
        agents)
            mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" \
                -e "DELETE FROM gewu.agent_tool WHERE migrated_from_opencode = 1;" 2>/dev/null || true
            mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" \
                -e "DELETE FROM gewu.agent WHERE migrated_from_opencode = 1;" 2>/dev/null || true
            log "  Rolled back agents and agent_tools"
            ;;
        sessions)
            mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" \
                -e "DELETE FROM gewu.session_message WHERE migrated_from_opencode = 1;" 2>/dev/null || true
            mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" \
                -e "DELETE FROM gewu.session WHERE migrated_from_opencode = 1;" 2>/dev/null || true
            mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" \
                -e "DELETE FROM gewu.project WHERE migrated_from_opencode = 1;" 2>/dev/null || true
            log "  Rolled back projects, sessions, and messages"
            ;;
        users)
            mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" \
                -e "DELETE FROM gewu.users WHERE migrated_from_opencode = 1;" 2>/dev/null || true
            log "  Rolled back users"
            ;;
    esac
done

python3 -c "
import json
data = json.load(open('$CHECKPOINT_FILE'))
data['last_completed'] = 'none'
data['completed_phases'] = []
data['rollback_at'] = '$(date -Iseconds)'
json.dump(data, open('$CHECKPOINT_FILE', 'w'), indent=2)
" 2>/dev/null || true

log "Checkpoint rollback completed successfully."
log "Log saved to: $LOG_FILE"
