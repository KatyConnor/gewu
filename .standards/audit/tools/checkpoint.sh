#!/usr/bin/env bash
# AETC 检查点管理器 — create / restore / list（规范 AETC-STD-001 V1.1 CKP-*）
# git 仓库: 快照存为 refs/aetc/cp/<name>（不动分支、不污染提交历史）
# 非 git 目录: 快照为 audit/checkpoints/<name>.tar.gz
# 登记表: audit/checkpoints/registry.log
# V1.1: ①log_event 兼容 .standards 集中布局 ②create auto 自动命名（CKP-002A 无git·R1步骤前）
#       ③备注含 protect/回退前 的检查点永不裁剪 ④restore 前自动建"回退前现场"保护快照（RLB-002）
#       ⑤创建后按 config.checkpoint_policy.retention 自动裁剪（CKP-006）
set -euo pipefail

NAME_RE='^[a-zA-Z0-9._-]+$'
PROTECT_RE='(protect|回退前|rlb.?002)'

die() { echo "[AETC][CKP] 错误: $*" >&2; exit 1; }
info() { echo "[AETC][CKP] $*"; }

find_root() {
  local d="$PWD"
  for _ in $(seq 1 20); do
    [ -d "$d/audit" ] && { ROOT="$d"; return 0; }
    [ "$d" = "/" ] && break
    d="$(dirname "$d")"
  done
  ROOT="$PWD"
}

find_root
AUDIT_DIR="$ROOT/audit"
CKP_DIR="$AUDIT_DIR/checkpoints"
REGISTRY="$CKP_DIR/registry.log"
mkdir -p "$CKP_DIR"

audit_log_py() {
  for cand in "$ROOT/.standards/audit/tools/audit_log.py" "$ROOT/audit/tools/audit_log.py"; do
    [ -f "$cand" ] && { echo "$cand"; return 0; }
  done
  return 1
}

log_event() {  # 尽力写入留痕，失败不阻断检查点本身
  local py
  py="$(audit_log_py)" || return 0
  command -v python3 >/dev/null 2>&1 || return 0
  local sess="" latest
  latest="$(ls -t "$AUDIT_DIR/traces"/*.jsonl 2>/dev/null | head -1 || true)"
  [ -n "$latest" ] && sess="$(basename "$latest" .jsonl)"
  local args=(CHECKPOINT --summary "检查点 $1" --detail "类型:$2 目标:$3" --project-root "$ROOT")
  [ -n "$sess" ] && args+=(--session "$sess")   # 追加到最近会话，避免碎片化审计链
  python3 "$py" "${args[@]}" >/dev/null 2>&1 || true
}

is_git() { git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; }

retention_count() {  # 读 config.checkpoint_policy.retention.count，默认 20
  local cfg n
  for cfg in "$ROOT/audit/config.json" "$ROOT/.standards/audit/config.json"; do
    if [ -f "$cfg" ] && command -v python3 >/dev/null 2>&1; then
      n="$(python3 -c "import json,sys;
try: print(int(json.load(open(sys.argv[1],encoding='utf-8'))['policy']['checkpoint_policy']['retention']['count']))
except Exception: sys.exit(1)" "$cfg" 2>/dev/null)" && { echo "$n"; return 0; }
    fi
  done
  echo 20
}

trim_registry() {  # CKP-006：裁剪超保留数的最旧非保护检查点
  command -v python3 >/dev/null 2>&1 || { info "（python3 不可用，跳过自动裁剪）"; return 0; }
  local keep
  keep="$(retention_count)"
  python3 - "$REGISTRY" "$keep" "$ROOT" <<'PY' || info "（裁跳过：登记表解析异常）"
import os, re, sys, subprocess
reg, keep, root = sys.argv[1], int(sys.argv[2]), sys.argv[3]
prot = re.compile(r'(protect|回退前|rlb[-._ ]?002)', re.I)
lines = [l.rstrip("\n") for l in open(reg, encoding="utf-8") if l.strip()]
rows, malformed = [], 0
for l in lines:
    f = l.split("\t")
    if len(f) < 5:                      # 畸形行（含旧版独立 note 行）重写时清理
        malformed += 1
        continue
    rows.append({"name": f[0], "typ": f[1], "target": f[2],
                 "note": "\t".join(f[4:]), "raw": "\t".join(f)})
deletable = [r for r in rows if not (r["note"] and prot.search(r["note"]))]
drop = deletable[:-keep] if len(deletable) > keep else []
dropset = {r["name"] for r in drop}
for r in drop:
    if r["typ"] == "tar":
        p = os.path.join(root, "audit", "checkpoints", r["target"])
        if os.path.isfile(p):
            os.remove(p)
    elif r["typ"] == "git":
        subprocess.run(["git", "-C", root, "update-ref", "-d",
                        "refs/aetc/cp/" + r["name"]],
                       capture_output=True)
with open(reg, "w", encoding="utf-8") as f:
    for r in rows:
        if r["name"] not in dropset:
            f.write(r["raw"] + "\n")
msg = []
if drop:
    msg.append("裁剪 %d 个最旧非保护检查点（保留 %d）：%s"
               % (len(drop), keep, ", ".join(r["name"] for r in drop)))
if malformed:
    msg.append("清理 %d 条畸形登记行" % malformed)
if msg:
    print("[AETC][CKP] 保留策略(CKP-006)：" + "；".join(msg))
PY
}

cmd_create() {
  local name="${1:-}" note="${2:-}"
  [ -n "$name" ] || die "用法: checkpoint.sh create <name|auto> [note]"
  if [ "$name" = "auto" ]; then
    name="r1-$(date '+%Y%m%d-%H%M%S')-$$"   # 含PID，避免秒级撞名
  fi
  echo "$name" | grep -Eq "$NAME_RE" || die "检查点名称仅限字母数字 . _ -"
  # CKP-001 唯一性护栏：同名检查点拒绝创建（防止登记表出现重复行）
  if [ -f "$REGISTRY" ] && awk -F'\t' -v n="$name" '$1==n{found=1} END{exit !found}' "$REGISTRY"; then
    die "检查点 '$name' 已存在（CKP-001 全项目唯一）；可用 create auto 自动命名"
  fi
  if is_git && git -C "$ROOT" rev-parse -q --verify "refs/aetc/cp/$name" >/dev/null 2>&1; then
    die "检查点 '$name' 已存在（git ref）"
  fi
  local ts; ts="$(date '+%Y-%m-%dT%H:%M:%S%z')"
  if is_git; then
    local gitdir tmp_index tree parent commit
    gitdir="$(git -C "$ROOT" rev-parse --absolute-git-dir 2>/dev/null || git -C "$ROOT" rev-parse --git-dir)"
    case "$gitdir" in /*) ;; *) gitdir="$ROOT/$gitdir";; esac
    tmp_index="$gitdir/aetcp-index"
    if git -C "$ROOT" rev-parse HEAD >/dev/null 2>&1; then
      parent="-p HEAD"
      GIT_INDEX_FILE="$tmp_index" git -C "$ROOT" read-tree HEAD
    else
      parent=""
      GIT_INDEX_FILE="$tmp_index" git -C "$ROOT" read-tree --empty
    fi
    # 审计轨迹不进快照：快照回退不得抹掉留痕（GEN-004）
    GIT_INDEX_FILE="$tmp_index" git -C "$ROOT" rm -r -q --cached -- audit 2>/dev/null || true
    GIT_INDEX_FILE="$tmp_index" git -C "$ROOT" add -A -- . ':(exclude)audit' 2>/dev/null \
      || GIT_INDEX_FILE="$tmp_index" git -C "$ROOT" add -A .
    tree="$(GIT_INDEX_FILE="$tmp_index" git -C "$ROOT" write-tree)"
    commit="$(git -C "$ROOT" -c user.name="AETC Checkpoint" -c user.email="aetc@checkpoint.local" \
      commit-tree "$tree" $parent -m "AETC checkpoint: $name")"
    git -C "$ROOT" update-ref "refs/aetc/cp/$name" "$commit"
    rm -f "$tmp_index"
    local note_field=""
    [ -n "$note" ] && note_field="$(printf '\tnote: %s' "$note")"
    printf '%s\tgit\t%s\t%s\t%s%s\n' "$name" "refs/aetc/cp/$name" "$commit" "$ts" "$note_field" >> "$REGISTRY"
    info "检查点已创建: $name = $commit (git ref，不含audit/)"
  else
    local tarball="$CKP_DIR/$name.tar.gz"
    tar -czf "$tarball" -C "$ROOT" \
        --exclude='./audit' --exclude='./.git' --exclude='./node_modules' . 2>/dev/null
    local note_field=""
    [ -n "$note" ] && note_field="$(printf '\tnote: %s' "$note")"
    printf '%s\ttar\t%s\t%s\t%s%s\n' "$name" "$name.tar.gz" "$(sha256sum "$tarball" | cut -d' ' -f1)" "$ts" "$note_field" >> "$REGISTRY"
    info "检查点已创建: $name = $name.tar.gz (tar，不含audit/)"
    info "（无 git 工作区：CKP-002A——每个 R1 写步骤前应建检查点，可用 create auto 自动命名）"
  fi
  log_event "$name" "checkpoint" "${2:-}"
  trim_registry
}

cmd_restore() {
  local name="" mode="safe" assume_yes="no" a
  for a in "$@"; do
    case "$a" in
      --hard) mode="hard" ;;
      --yes|-y) assume_yes="yes" ;;
      *) [ -n "$name" ] || name="$a" ;;
    esac
  done
  [ -n "$name" ] || die "用法: checkpoint.sh restore <name> [--hard] [--yes]"
  awk -F'\t' -v n="$name" '$1==n{f=1} END{exit !f}' "$REGISTRY" 2>/dev/null || die "检查点不存在: $name（先 list 查看）"
  local type target; type="$(awk -F'\t' -v n="$name" '$1==n{print $2; exit}' "$REGISTRY")"
  target="$(awk -F'\t' -v n="$name" '$1==n{print $3; exit}' "$REGISTRY")"
  echo "[AETC][CKP] 即将恢复到检查点 $name（$type / $target）"
  [ "$mode" = "safe" ] && echo "  safe 模式：工作区内容恢复为检查点状态；新增未跟踪文件将保留"
  [ "$mode" = "hard" ] && echo "  hard 模式：git reset --hard，工作区完全回到检查点（未跟踪文件保留，需人工清理）"
  if [ "$assume_yes" != "yes" ]; then
    read -r -p "确认恢复? 输入 yes 继续: " ok
    [ "$ok" = "yes" ] || die "已取消"
  fi
  # RLB-002 落地：回退前先建"回退前现场"保护检查点（失败即中止——可回退优先）
  local pre="pre-restore-$(date '+%Y%m%d-%H%M%S')"
  info "回退前快照（RLB-002 保存问题现场）: $pre"
  cmd_create "$pre" "RLB-002 回退前现场 protect" >/dev/null
  if [ "$type" = "git" ]; then
    local gitdir ridx
    gitdir="$(git -C "$ROOT" rev-parse --absolute-git-dir 2>/dev/null || git -C "$ROOT" rev-parse --git-dir)"
    case "$gitdir" in /*) ;; *) gitdir="$ROOT/$gitdir";; esac
    ridx="$gitdir/aetcp-restore"
    if [ "$mode" = "hard" ]; then
      # hard: 完全重置。先备份审计目录，重置后立即还原，防审计轨迹被回滚（GEN-004）
      local bk="$CKP_DIR/audit-backup-$(date '+%Y%m%d-%H%M%S').tar.gz"
      tar -czf "$bk" -C "$ROOT" ./audit 2>/dev/null || true
      git -C "$ROOT" reset --hard "$target"
      [ -f "$bk" ] && tar -xzf "$bk" -C "$ROOT" 2>/dev/null || true
      info "hard 恢复完成；audit/ 已先备份($bk)并于重置后还原，审计轨迹完整"
    else
      # safe: 以检查点内容覆盖工作区（不删除任何现有文件，audit/与未跟踪文件全部保留）
      rm -f "$ridx"
      GIT_INDEX_FILE="$ridx" git -C "$ROOT" read-tree "$target"
      GIT_INDEX_FILE="$ridx" git -C "$ROOT" checkout-index -a -f
      rm -f "$ridx"
      info "已恢复到 $name（safe：检查点文件已覆盖还原，新增文件与audit/保留）"
    fi
  else
    tar -xzf "$CKP_DIR/$name.tar.gz" -C "$ROOT"
    info "已从 tar 快照恢复到 $name"
  fi
  info "恢复后请执行验证（构建/测试/冒烟），并按 RLB-004 填写 rollback-record.md"
}

cmd_list() {
  echo "AETC 检查点登记表（$ROOT）"
  [ -f "$REGISTRY" ] || { echo "  （暂无检查点）"; exit 0; }
  printf '%-28s %-5s %-30s %-8s %s\n' "NAME" "TYPE" "TARGET" "PROTECT" "TIME"
  awk -F'\t' 'NF>=5{prot=($0 ~ /(protect|回退前|rlb[-._ ]?002)/)?"yes":"-";
    printf "%-28s %-5s %-30s %-8s %s\n",$1,$2,substr($3,1,30),prot,$5}' "$REGISTRY"
  if is_git; then
    git -C "$ROOT" for-each-ref --format='  git-ref: %(refname:short) %(objectname:short)' refs/aetc/cp || true
  fi
}

case "${1:-}" in
  create) shift; cmd_create "$@" ;;
  restore) shift; cmd_restore "$@" ;;
  list) shift; cmd_list "$@" ;;
  *) echo "用法: checkpoint.sh create <name|auto> [note] | restore <name> [--hard] [--yes] | list"; exit 2 ;;
esac
