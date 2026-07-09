#!/usr/bin/env bash
# backup-before-migration.sh — Wrapper that calls both backup scripts
# Records backup timestamps, file sizes, checksums, and outputs summary.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./migration-config.sh
source "${SCRIPT_DIR}/migration-config.sh"

BACKUP_DIR="${BACKUP_DIR:-/data/backups}"
BACKUP_LOG="${BACKUP_DIR}/backup-manifest-$(date +%Y%m%d_%H%M%S).log"
OVERALL_EXIT=0

mkdir -p "${BACKUP_DIR}"

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  Pre-Migration Backup — $(date)"
echo "═══════════════════════════════════════════════════════════════"
echo ""

echo "Backup Manifest — $(date)" > "${BACKUP_LOG}"
echo "========================================" >> "${BACKUP_LOG}"

# ── Backup OpenCode ────────────────────────────────────────────────────────────
echo ">>> Backing up OpenCode database..."
log_info "Starting OpenCode backup..."

OPencode_START="$(date +%s)"
if "${SCRIPT_DIR}/backup-opencode.sh" 2>&1 | tee -a "${BACKUP_LOG}"; then
  OPencode_END="$(date +%s)"
  OPencode_DURATION=$((OPencode_END - OPencode_START))
  log_info "OpenCode backup succeeded (${OPencode_DURATION}s)"
  echo "[PASS] OpenCode backup: ${OPencode_DURATION}s" >> "${BACKUP_LOG}"
else
  OPencode_END="$(date +%s)"
  OPencode_DURATION=$((OPencode_END - OPencode_START))
  log_error "OpenCode backup FAILED after ${OPencode_DURATION}s"
  echo "[FAIL] OpenCode backup: ${OPencode_DURATION}s" >> "${BACKUP_LOG}"
  OVERALL_EXIT=1
fi
echo ""

# ── Backup Gewu ────────────────────────────────────────────────────────────────
echo ">>> Backing up Gewu database..."
log_info "Starting Gewu backup..."

GEWU_START="$(date +%s)"
if "${SCRIPT_DIR}/backup-gewu.sh" 2>&1 | tee -a "${BACKUP_LOG}"; then
  GEWU_END="$(date +%s)"
  GEWU_DURATION=$((GEWU_END - GEWU_START))
  log_info "Gewu backup succeeded (${GEWU_DURATION}s)"
  echo "[PASS] Gewu backup: ${GEWU_DURATION}s" >> "${BACKUP_LOG}"
else
  GEWU_END="$(date +%s)"
  GEWU_DURATION=$((GEWU_END - GEWU_START))
  log_error "Gewu backup FAILED after ${GEWU_DURATION}s"
  echo "[FAIL] Gewu backup: ${GEWU_DURATION}s" >> "${BACKUP_LOG}"
  OVERALL_EXIT=1
fi
echo ""

# ── Collect backup file details ────────────────────────────────────────────────
echo ">>> Collecting backup file details..."

BACKUP_FILES=()
while IFS= read -r -d '' f; do
  BACKUP_FILES+=("${f}")
done < <(find "${BACKUP_DIR}" -maxdepth 1 -name "*-pre-migration-$(date +%Y%m%d)*.sql.gz" -print0 2>/dev/null || true)

echo "" >> "${BACKUP_LOG}"
echo "Backup File Details:" >> "${BACKUP_LOG}"
echo "--------------------" >> "${BACKUP_LOG}"

for file in "${BACKUP_FILES[@]}"; do
  size="$(stat -c%s "${file}" 2>/dev/null || stat -f%z "${file}" 2>/dev/null)"
  size_human="$(numfmt --to=iec "${size}" 2>/dev/null || echo "${size} bytes")"
  cksum="$(sha256sum "${file}" | cut -d' ' -f1)"
  echo "  File:       ${file}" >> "${BACKUP_LOG}"
  echo "  Size:       ${size_human}" >> "${BACKUP_LOG}"
  echo "  SHA256:     ${cksum}" >> "${BACKUP_LOG}"
  echo "" >> "${BACKUP_LOG}"
done

# ── Summary ────────────────────────────────────────────────────────────────────
echo "═══════════════════════════════════════════════════════════════"
echo "  Pre-Migration Backup Summary"
echo "═══════════════════════════════════════════════════════════════"

for file in "${BACKUP_FILES[@]}"; do
  size="$(stat -c%s "${file}" 2>/dev/null || stat -f%z "${file}" 2>/dev/null)"
  size_human="$(numfmt --to=iec "${size}" 2>/dev/null || echo "${size} bytes")"
  echo "  ✓ $(basename "${file}") — ${size_human}"
done

echo ""
echo "  Manifest: ${BACKUP_LOG}"
echo ""

if [[ "${OVERALL_EXIT}" -eq 0 ]]; then
  echo "  Result: ALL BACKUPS SUCCEEDED"
else
  echo "  Result: SOME BACKUPS FAILED"
fi

echo "═══════════════════════════════════════════════════════════════"

exit "${OVERALL_EXIT}"
