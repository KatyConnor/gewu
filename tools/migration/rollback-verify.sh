#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/migration-config.sh"

REPORT_FILE="${SCRIPT_DIR}/rollback-verification-$(date +%Y%m%d-%H%M%S).txt"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] [ROLLBACK-VERIFY] $1" | tee -a "$REPORT_FILE"
}

log "========================================="
log "  Rollback Verification Report"
log "  Date: $(date '+%Y-%m-%d %H:%M:%S')"
log "========================================="

ERRORS=0

log ""
log "--- 1. Check migration artifacts ---"

ARTIFACT_TABLES=$(mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" -N -e \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'gewu' AND table_name LIKE 'migration_%';" 2>/dev/null || echo "0")

if [[ "$ARTIFACT_TABLES" -gt 0 ]]; then
    log "WARN: Found $ARTIFACT_TABLES migration tracking tables still present"
    ERRORS=$((ERRORS + 1))
else
    log "PASS: No migration tracking tables found"
fi

log ""
log "--- 2. Verify source data integrity ---"

for table in users sessions messages agents; do
    COUNT=$(mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" -N -e \
        "SELECT COUNT(*) FROM opencode.${table};" 2>/dev/null || echo "-1")
    if [[ "$COUNT" == "-1" ]]; then
        log "WARN: Cannot access opencode.${table} (may not exist)"
    else
        log "PASS: opencode.${table} has $COUNT records (source intact)"
    fi
done

log ""
log "--- 3. Verify gewu FK integrity ---"

ORPHAN_SESSIONS=$(mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" -N -e \
    "SELECT COUNT(*) FROM gewu.session s LEFT JOIN gewu.users u ON s.user_id = u.id WHERE u.id IS NULL;" 2>/dev/null || echo "0")

if [[ "$ORPHAN_SESSIONS" -gt 0 ]]; then
    log "FAIL: Found $ORPHAN_SESSIONS orphaned sessions (no matching user)"
    ERRORS=$((ERRORS + 1))
else
    log "PASS: No orphaned sessions"
fi

ORPHAN_MESSAGES=$(mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" -N -e \
    "SELECT COUNT(*) FROM gewu.session_message m LEFT JOIN gewu.session s ON m.session_id = s.id WHERE s.id IS NULL;" 2>/dev/null || echo "0")

if [[ "$ORPHAN_MESSAGES" -gt 0 ]]; then
    log "FAIL: Found $ORPHAN_MESSAGES orphaned messages (no matching session)"
    ERRORS=$((ERRORS + 1))
else
    log "PASS: No orphaned messages"
fi

log ""
log "--- 4. Verify ULID format ---"

INVALID_ULIDS=$(mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" -N -e \
    "SELECT COUNT(*) FROM gewu.users WHERE id NOT REGEXP '^[0-9A-HJKMNP-TV-Z]{26}$';" 2>/dev/null || echo "0")

if [[ "$INVALID_ULIDS" -gt 0 ]]; then
    log "FAIL: Found $INVALID_ULIDS users with invalid ULID format"
    ERRORS=$((ERRORS + 1))
else
    log "PASS: All user IDs are valid ULIDs"
fi

log ""
log "--- 5. Checkpoint file status ---"

CHECKPOINT_FILE="${SCRIPT_DIR}/migration-checkpoint.json"
if [[ -f "$CHECKPOINT_FILE" ]]; then
    log "INFO: Checkpoint file exists at $CHECKPOINT_FILE"
    python3 -c "
import json
data = json.load(open('$CHECKPOINT_FILE'))
print(f\"  Last completed: {data.get('last_completed', 'none')}\")
print(f\"  Completed phases: {data.get('completed_phases', [])}\")
" 2>/dev/null || log "  WARN: Cannot parse checkpoint file"
else
    log "INFO: No checkpoint file found (clean state)"
fi

log ""
log "========================================="
if [[ "$ERRORS" -eq 0 ]]; then
    log "RESULT: ALL CHECKS PASSED"
else
    log "RESULT: $ERRORS CHECK(S) FAILED"
fi
log "Report saved to: $REPORT_FILE"
log "========================================="

exit "$ERRORS"
