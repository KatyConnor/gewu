#!/usr/bin/env bash
# compare-sampling.sh — Random sampling comparison between source and target
# Takes a 5% random sample from both opencode_* and gewu.* tables,
# compares field-by-field, and reports differences.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./migration-config.sh
source "${SCRIPT_DIR}/migration-config.sh"

SAMPLE_PERCENT="${SAMPLE_PERCENT:-5}"
TOTAL_PASS=0
TOTAL_FAIL=0
TOTAL_SKIP=0
SAMPLE_LIMIT=0

usage() {
  echo "Usage: $0 [--percent N] [--limit N]"
  echo "  --percent N  Sample percentage (default: 5)"
  echo "  --limit N    Max sample rows per table (overrides percentage)"
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --percent) SAMPLE_PERCENT="$2"; shift 2 ;;
    --limit)   SAMPLE_LIMIT="$2"; shift 2 ;;
    --help|-h) usage ;;
    *) echo "Unknown option: $1"; usage ;;
  esac
done

check_mysql || exit 1

compare_table() {
  local source_table="$1"
  local target_table="$2"
  local id_column="${3:-id}"
  local columns="${4:-*}"

  log_info "Sampling ${source_table} → ${target_table}..."

  local src_count
  src_count="$(mysql_opencode "SELECT COUNT(*) FROM ${source_table}")"

  if [[ "${src_count}" -eq 0 ]]; then
    log_warn "  Source table ${source_table} is empty, skipping"
    TOTAL_SKIP=$((TOTAL_SKIP + 1))
    return 0
  fi

  # Determine sample size
  local sample_size
  if [[ "${SAMPLE_LIMIT}" -gt 0 ]]; then
    sample_size="${SAMPLE_LIMIT}"
  else
    sample_size="$(( src_count * SAMPLE_PERCENT / 100 ))"
    [[ "${sample_size}" -lt 1 ]] && sample_size=1
  fi

  log_info "  Source count: ${src_count}, sampling ${sample_size} rows"

  # Fetch random sample IDs from source
  local src_ids
  src_ids="$(mysql_opencode "
    SELECT ${id_column}
    FROM ${source_table}
    ORDER BY RAND()
    LIMIT ${sample_size}
  ")"

  local row_num=0
  while IFS= read -r id; do
    [[ -z "${id}" ]] && continue
    row_num=$((row_num + 1))

    # Fetch source row
    local src_row
    src_row="$(mysql_opencode "
      SELECT ${columns}
      FROM ${source_table}
      WHERE ${id_column} = '${id}'
    " 2>/dev/null || true)"

    # Fetch target row
    local tgt_row
    tgt_row="$(mysql_gewu "
      SELECT ${columns}
      FROM ${target_table}
      WHERE ${id_column} = '${id}'
    " 2>/dev/null || true)"

    if [[ -z "${src_row}" ]] && [[ -z "${tgt_row}" ]]; then
      echo "  [SKIP] Row ${id}: both empty"
      TOTAL_SKIP=$((TOTAL_SKIP + 1))
      continue
    fi

    if [[ -z "${tgt_row}" ]]; then
      echo "  [FAIL] ${source_table}.${id}: missing in target"
      TOTAL_FAIL=$((TOTAL_FAIL + 1))
      continue
    fi

    if [[ "${src_row}" == "${tgt_row}" ]]; then
      TOTAL_PASS=$((TOTAL_PASS + 1))
      continue
    fi

    # Field-by-field comparison
    local src_fields tgt_fields
    IFS=$'\t' read -ra src_fields <<< "${src_row}"
    IFS=$'\t' read -ra tgt_fields <<< "${tgt_row}"

    # Get column names
    local col_names
    col_names="$(mysql_opencode "
      SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY ORDINAL_POSITION SEPARATOR '\t')
      FROM INFORMATION_SCHEMA.COLUMNS
      WHERE TABLE_SCHEMA = '${OPENCODE_DB}' AND TABLE_NAME = '${source_table}'
    ")"

    IFS=$'\t' read -ra col_names_arr <<< "${col_names}"

    echo "  [DIFF] ${source_table}.${id}:"
    local field_diffs=0
    for idx in "${!src_fields[@]}"; do
      local col="${col_names_arr[${idx}]:-col${idx}}"
      local sv="${src_fields[${idx}]:-}"
      local tv="${tgt_fields[${idx}]:-}"

      # Normalize null representations
      sv="${sv:-NULL}"
      tv="${tv:-NULL}"

      if [[ "${sv}" != "${tv}" ]]; then
        echo "    ${col}: '${sv}' → '${tv}'"
        field_diffs=$((field_diffs + 1))
      fi
    done

    if [[ "${field_diffs}" -eq 0 ]]; then
      # No visible diff (e.g. whitespace) but rows compared differently
      echo "    (whitespace or formatting difference only)"
      TOTAL_PASS=$((TOTAL_PASS + 1))
    else
      TOTAL_FAIL=$((TOTAL_FAIL + 1))
    fi
  done <<< "${src_ids}"
}

# ── Table mapping ──────────────────────────────────────────────────────────────
# (source_table, target_table, id_column, columns)
TABLES=(
  "opencode_users:users:id:*"
  "opencode_sessions:sessions:id:*"
  "opencode_session_messages:session_messages:id:*"
  "opencode_agents:agents:id:*"
)

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  Random Sampling Comparison (${SAMPLE_PERCENT}%)"
echo "  Started: $(date)"
echo "═══════════════════════════════════════════════════════════════"
echo ""

for entry in "${TABLES[@]}"; do
  IFS=':' read -r src tgt id cols <<< "${entry}"
  compare_table "${src}" "${tgt}" "${id}" "${cols}"
  echo ""
done

echo "───────────────────────────────────────────────────────────────"
echo "  Comparison Summary"
echo "───────────────────────────────────────────────────────────────"
echo "  Passed:  ${TOTAL_PASS}"
echo "  Failed:  ${TOTAL_FAIL}"
echo "  Skipped: ${TOTAL_SKIP}"
echo ""

if [[ "${TOTAL_FAIL}" -eq 0 ]]; then
  echo "  RESULT: PASS"
  exit 0
else
  echo "  RESULT: FAIL — ${TOTAL_FAIL} record(s) differ"
  exit 1
fi
