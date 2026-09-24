#!/usr/bin/env bash
# AETC 留痕写入器（bash 降级版）— 无 python3 环境时使用
# 优先委托 audit_log.py；完全无 python3 时以纯 bash 写入等价 JSONL 记录（含哈希链）
# 用法与 audit_log.py 相同:
#   audit_log.sh EVENT --phase coding --summary "..." [--detail ...] [--risk R1]
#                [--artifacts a.py,b.py] [--skill ...] [--decision ...] [--ref N]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
if command -v python3 >/dev/null 2>&1; then
  exec python3 "$HERE/audit_log.py" "$@"
fi

# ---------- 纯 bash 实现 ----------
EVENTS="TASK_OPEN PLAN_COMMIT STEP_DONE DECISION SKILL_USE RISK_PRE RISK_POST CHECKPOINT GATE_REQUEST GATE_RESULT BLOCKED CORRECTION TASK_CLOSE"
PHASES="requirements design coding testing release ops retro"

EVENT=""; PHASE=""; SUMMARY=""; DETAIL=""; RISK=""; ARTIFACTS=""; SKILL=""; DECISION=""; ACTOR="agent"
SESSION="${AETC_SESSION:-}"; REF=""; ROOT="${AETC_PROJECT_ROOT:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --phase) PHASE="$2"; shift 2 ;;
    --summary) SUMMARY="$2"; shift 2 ;;
    --detail) DETAIL="$2"; shift 2 ;;
    --risk) RISK="$2"; shift 2 ;;
    --artifacts) ARTIFACTS="$2"; shift 2 ;;
    --skill) SKILL="$2"; shift 2 ;;
    --decision) DECISION="$2"; shift 2 ;;
    --actor) ACTOR="$2"; shift 2 ;;
    --session) SESSION="$2"; shift 2 ;;
    --ref) REF="$2"; shift 2 ;;
    --project-root) ROOT="$2"; shift 2 ;;
    -*) echo "[AETC] 未知参数: $1" >&2; exit 2 ;;
    *) [ -z "$EVENT" ] && EVENT="$1" || { echo "[AETC] 多余位置参数" >&2; exit 2; }; shift ;;
  esac
done

EVENT="$(echo "$EVENT" | tr 'a-z' 'A-Z')"
echo " $EVENTS " | grep -q " $EVENT " || { echo "[AETC] 非法事件: $EVENT" >&2; exit 2; }
[ -n "$PHASE" ] && { echo " $PHASES " | grep -q " $PHASE " || { echo "[AETC] 非法阶段: $PHASE" >&2; exit 2; }; }
case "$RISK" in ""|R0|R1|R2|R3) ;; *) echo "[AETC] 非法风险: $RISK" >&2; exit 2 ;; esac
[ -n "$SUMMARY" ] || { echo "[AETC] --summary 必填" >&2; exit 2; }

if [ -z "$ROOT" ]; then
  d="$PWD"; n=0
  while [ $n -lt 20 ]; do [ -d "$d/audit" ] && { ROOT="$d"; break; }; d="$(dirname "$d")"; n=$((n + 1)); done
  [ -n "${ROOT:-}" ] || ROOT="$PWD"
fi
TRACES="$ROOT/audit/traces"
mkdir -p "$TRACES"
[ -n "$SESSION" ] || SESSION="s-$(date '+%Y%m%d-%H%M%S')"
FILE="$TRACES/$SESSION.jsonl"

esc() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr -d '\n\r\t'; }

PREV="null"; SEQ=0
if [ -f "$FILE" ] && [ -s "$FILE" ]; then
  LAST="$(tail -n 1 "$FILE")"
  PREV="$(printf '%s' "$LAST" | grep -o '"hash": *"[a-f0-9]*"' | sed 's/.*"\([a-f0-9]*\)"/\1/')"
  SEQ="$(printf '%s' "$LAST" | grep -o '"seq": *[0-9]*' | grep -o '[0-9]*')"
  [ -n "$PREV" ] || PREV="null"
  [ -n "$SEQ" ] || SEQ=0
fi
SEQ=$((SEQ + 1))
TS="$(date '+%Y-%m-%dT%H:%M:%S%:z')"
ARTS=""
if [ -n "$ARTIFACTS" ]; then
  ARTS='['
  first=1
  IFS=','; for a in $ARTIFACTS; do
    a="${a# }"; a="${a% }"; [ -z "$a" ] && continue
    [ $first -eq 0 ] && ARTS="$ARTS,"
    ARTS="$ARTS\"$(esc "$a")\""
    first=0
  done
  unset IFS
  ARTS="$ARTS]"
fi
PREV_Q="null"; [ "$PREV" != "null" ] && PREV_Q="\"$PREV\""

# 规范化串（与 audit_check.py 的 canon 一致：键排序、紧凑分隔符）
CANON="{\"actor\":\"$(esc "$ACTOR")\""
[ -n "$ARTS" ] && CANON="$CANON,\"artifacts\":$ARTS"
[ -n "$DECISION" ] && CANON="$CANON,\"decision\":\"$(esc "$DECISION")\""
[ -n "$DETAIL" ] && CANON="$CANON,\"detail\":\"$(esc "$DETAIL")\""
CANON="$CANON,\"event\":\"$EVENT\""
[ -n "$PHASE" ] && CANON="$CANON,\"phase\":\"$PHASE\""
CANON="$CANON,\"prev_hash\":$PREV_Q"
[ -n "$REF" ] && CANON="$CANON,\"ref\":$REF"
[ -n "$RISK" ] && CANON="$CANON,\"risk\":\"$RISK\""
CANON="$CANON,\"schema\":\"aetc/1.0\",\"seq\":$SEQ,\"session\":\"$(esc "$SESSION")\""
[ -n "$SKILL" ] && CANON="$CANON,\"skill\":\"$(esc "$SKILL")\""
CANON="$CANON,\"summary\":\"$(esc "$SUMMARY")\",\"ts\":\"$TS\"}"
HASH="$(printf '%s' "$CANON" | sha256sum | cut -d' ' -f1)"

REC="{\"schema\":\"aetc/1.0\",\"seq\":$SEQ,\"session\":\"$(esc "$SESSION")\",\"ts\":\"$TS\",\"actor\":\"$(esc "$ACTOR")\",\"event\":\"$EVENT\",\"summary\":\"$(esc "$SUMMARY")\""
[ -n "$PHASE" ] && REC="$REC,\"phase\":\"$PHASE\""
[ -n "$DETAIL" ] && REC="$REC,\"detail\":\"$(esc "$DETAIL")\""
[ -n "$RISK" ] && REC="$REC,\"risk\":\"$RISK\""
[ -n "$ARTS" ] && REC="$REC,\"artifacts\":$ARTS"
[ -n "$SKILL" ] && REC="$REC,\"skill\":\"$(esc "$SKILL")\""
[ -n "$DECISION" ] && REC="$REC,\"decision\":\"$(esc "$DECISION")\""
[ -n "$REF" ] && REC="$REC,\"ref\":$REF"
REC="$REC,\"prev_hash\":$PREV_Q,\"hash\":\"$HASH\"}"

printf '%s\n' "$REC" >> "$FILE"
echo "[AETC] $EVENT seq=$SEQ session=$SESSION -> $FILE"
