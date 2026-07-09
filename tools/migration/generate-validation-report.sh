#!/usr/bin/env bash
# generate-validation-report.sh — Generate HTML validation report from validation results
# Called by validate-all.sh after all validations complete.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./migration-config.sh
source "${SCRIPT_DIR}/migration-config.sh"

REPORT_FILE="${REPORT_FILE:-${PROJECT_ROOT}/deploy/reports/migration-validation-$(date +%Y%m%d).html}"
TIMESTAMP="$(date '+%Y-%m-%d %H:%M:%S')"

usage() {
  echo "Usage: $0 [--input <validation-results-file>]"
  echo "  --input   Read validation results from file instead of stdin (default: stdin)"
  exit 1
}

INPUT_FILE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --input) INPUT_FILE="$2"; shift 2 ;;
    --help|-h) usage ;;
    *) echo "Unknown option: $1"; usage ;;
  esac
done

RESULTS=""
if [[ -n "${INPUT_FILE}" ]]; then
  if [[ ! -f "${INPUT_FILE}" ]]; then
    log_error "Input file not found: ${INPUT_FILE}"
    exit 1
  fi
  RESULTS="$(cat "${INPUT_FILE}")"
else
  RESULTS="$(cat)"
fi

report_dir="$(dirname "${REPORT_FILE}")"
mkdir -p "${report_dir}"

parse_section() {
  local section="$1"
  echo "${RESULTS}" | awk -v s="${section}:" '
    $0 ~ "^\\[.*\\] " s ":" {flag=1; next}
    flag && /^\[/ && $0 !~ s":" {flag=0}
    flag {print}
  '
}

parse_status() {
  local section="$1"
  echo "${RESULTS}" | grep -E "\[.*\] ${section}:" | sed -E 's/.*\[(PASS|FAIL|WARN)\].*/\1/' || echo "UNKNOWN"
}

parse_count() {
  local section="$1"
  echo "${RESULTS}" | grep -E "^\[.*\] ${section}:" | sed -E 's/.*: //'
}

get_overall_status() {
  if echo "${RESULTS}" | grep -q "OVERALL: FAIL"; then
    echo "FAIL"
  elif echo "${RESULTS}" | grep -q "OVERALL: PASS"; then
    echo "PASS"
  else
    echo "UNKNOWN"
  fi
}

OVERALL=$(get_overall_status)

cat > "${REPORT_FILE}" <<REPORT_EOF
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Migration Validation Report — ${TIMESTAMP}</title>
<style>
  :root {
    --bg: #f5f7fa;
    --card: #ffffff;
    --text: #1a1a2e;
    --text-secondary: #6b7280;
    --pass: #10b981;
    --pass-bg: #d1fae5;
    --fail: #ef4444;
    --fail-bg: #fee2e2;
    --warn: #f59e0b;
    --warn-bg: #fef3c7;
    --border: #e5e7eb;
  }
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    background: var(--bg);
    color: var(--text);
    padding: 2rem;
    line-height: 1.6;
  }
  .container { max-width: 960px; margin: 0 auto; }
  h1 {
    font-size: 1.75rem;
    margin-bottom: 0.25rem;
    display: flex;
    align-items: center;
    gap: 0.75rem;
  }
  .subtitle { color: var(--text-secondary); margin-bottom: 2rem; font-size: 0.9rem; }
  .overall-badge {
    display: inline-block;
    padding: 0.35rem 1rem;
    border-radius: 999px;
    font-weight: 700;
    font-size: 0.85rem;
    text-transform: uppercase;
    letter-spacing: 0.05em;
  }
  .overall-badge.pass { background: var(--pass-bg); color: #065f46; }
  .overall-badge.fail { background: var(--fail-bg); color: #991b1b; }
  .overall-badge.unknown { background: #f3f4f6; color: #6b7280; }

  .summary-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
    gap: 1rem;
    margin-bottom: 2rem;
  }
  .summary-card {
    background: var(--card);
    border-radius: 0.75rem;
    padding: 1.25rem;
    border: 1px solid var(--border);
    box-shadow: 0 1px 3px rgba(0,0,0,0.05);
  }
  .summary-card .label { font-size: 0.8rem; color: var(--text-secondary); text-transform: uppercase; letter-spacing: 0.05em; }
  .summary-card .value { font-size: 1.5rem; font-weight: 700; margin-top: 0.25rem; }

  table {
    width: 100%;
    border-collapse: collapse;
    background: var(--card);
    border-radius: 0.75rem;
    overflow: hidden;
    border: 1px solid var(--border);
    box-shadow: 0 1px 3px rgba(0,0,0,0.05);
    margin-bottom: 2rem;
  }
  th, td { padding: 0.75rem 1rem; text-align: left; border-bottom: 1px solid var(--border); }
  th { background: #f9fafb; font-size: 0.8rem; text-transform: uppercase; letter-spacing: 0.05em; color: var(--text-secondary); font-weight: 600; }
  tr:last-child td { border-bottom: none; }
  .status-pass { color: var(--pass); font-weight: 600; }
  .status-fail { color: var(--fail); font-weight: 600; }
  .status-warn { color: var(--warn); font-weight: 600; }

  .detail-section {
    background: var(--card);
    border-radius: 0.75rem;
    padding: 1.25rem;
    border: 1px solid var(--border);
    margin-bottom: 1rem;
  }
  .detail-section h3 { margin-bottom: 0.75rem; font-size: 1rem; }
  .detail-section pre {
    background: #1f2937;
    color: #e5e7eb;
    padding: 1rem;
    border-radius: 0.5rem;
    overflow-x: auto;
    font-size: 0.8rem;
    line-height: 1.5;
    max-height: 400px;
    overflow-y: auto;
  }
  .rejected-record { border-left: 3px solid var(--fail); padding-left: 0.75rem; margin: 0.5rem 0; font-family: monospace; font-size: 0.8rem; }
  .footer { text-align: center; color: var(--text-secondary); font-size: 0.8rem; margin-top: 2rem; padding-top: 1rem; border-top: 1px solid var(--border); }
</style>
</head>
<body>
<div class="container">
  <h1>
    Migration Validation Report
    <span class="overall-badge ${OVERALL,,}">${OVERALL}</span>
  </h1>
  <div class="subtitle">Generated: ${TIMESTAMP}</div>

  <div class="summary-grid">
    <div class="summary-card">
      <div class="label">Passed</div>
      <div class="value" style="color:var(--pass)">$(echo "${RESULTS}" | grep -c '\[PASS\]' || true)</div>
    </div>
    <div class="summary-card">
      <div class="label">Failed</div>
      <div class="value" style="color:var(--fail)">$(echo "${RESULTS}" | grep -c '\[FAIL\]' || true)</div>
    </div>
    <div class="summary-card">
      <div class="label">Warnings</div>
      <div class="value" style="color:var(--warn)">$(echo "${RESULTS}" | grep -c '\[WARN\]' || true)</div>
    </div>
  </div>

  <table>
    <thead>
      <tr>
        <th>Validation</th>
        <th>Status</th>
        <th>Details</th>
      </tr>
    </thead>
    <tbody>
REPORT_EOF

# Parse each validation section and add table rows
while IFS= read -r line; do
  if [[ "${line}" =~ ^\[(PASS|FAIL|WARN)\]\ ([^:]+):\ (.*) ]]; then
    status="${BASH_REMATCH[1]}"
    vname="${BASH_REMATCH[2]}"
    detail="${BASH_REMATCH[3]}"
    status_class="status-${status,,}"
    cat >> "${REPORT_FILE}" <<ROW_EOF
      <tr>
        <td>${vname}</td>
        <td class="${status_class}">${status}</td>
        <td>${detail}</td>
      </tr>
ROW_EOF
  fi
done <<< "${RESULTS}"

cat >> "${REPORT_FILE}" <<REPORT_EOF
    </tbody>
  </table>
REPORT_EOF

# Rejected records section
rejected="$(echo "${RESULTS}" | grep -A 9999 '\[REJECTED\]' || true)"
if [[ -n "${rejected}" ]]; then
  cat >> "${REPORT_FILE}" <<REPORT_EOF
  <div class="detail-section">
    <h3>Rejected Records</h3>
    <pre>$(echo "${rejected}" | sed 's/</\&lt;/g; s/>/\&gt;/g')</pre>
  </div>
REPORT_EOF
fi

# Full raw output section
cat >> "${REPORT_FILE}" <<REPORT_EOF
  <div class="detail-section">
    <h3>Raw Validation Output</h3>
    <pre>$(echo "${RESULTS}" | sed 's/</\&lt;/g; s/>/\&gt;/g')</pre>
  </div>
REPORT_EOF

# Detailed rejected records
detail_rejected=$(echo "${RESULTS}" | grep -E '^\[REJECTED\]' || true)
if [[ -n "${detail_rejected}" ]]; then
  cat >> "${REPORT_FILE}" <<REPORT_EOF
  <div class="detail-section">
    <h3>Rejected Records Detail</h3>
REPORT_EOF
  while IFS= read -r rej; do
    esc_rej="$(echo "${rej}" | sed 's/</\&lt;/g; s/>/\&gt;/g')"
    cat >> "${REPORT_FILE}" <<REJ_EOF
    <div class="rejected-record">${esc_rej}</div>
REJ_EOF
  done <<< "${detail_rejected}"
  echo "</div>" >> "${REPORT_FILE}"
fi

cat >> "${REPORT_FILE}" <<REPORT_EOF
  <div class="footer">
    Report generated by OpenCode → Gewu Migration Toolkit &bull; ${TIMESTAMP}
  </div>
</div>
</body>
</html>
REPORT_EOF

log_info "Validation report generated: ${REPORT_FILE}"
echo "REPORT_FILE=${REPORT_FILE}"
