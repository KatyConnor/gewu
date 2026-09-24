#!/usr/bin/env bash
# AETC 检查点管理器 — create / restore / list（规范 AETC-STD-001 CKP-*）
# git 仓库: 快照存为 refs/aetc/cp/<name>（不动分支、不污染提交历史）
# 非 git 目录: 快照为 audit/checkpoints/<name>.tar.gz
# 登记表: audit/checkpoints/registry.log
set -euo pipefail

NAME_RE='^[a-zA-Z0-9._-]+$'

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

log_event() {  # 尽力写入留痕，失败不阻断检查点本身
  if command -v python3 >/dev/null 2>&1 && [ -f "$AUDIT_DIR/tools/audit_log.py" ]; then
    python3 "$AUDIT_DIR/tools/audit_log.py" CHECKPOINT --summary "检查点 $1" \
      --detail "类型:$2 目标:$3" --project-root "$ROOT" >/dev/null 2>&1 || true
  fi
}

is_git() { git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; }

cmd_create() {
  local name="${1:-}" note="${2:-}"
  [ -n "$name" ] || die "用法: checkpoint.sh create <name> [note]"
  echo "$name" | grep -Eq "$NAME_RE" || die "检查点名称仅限字母数字 . _ -"
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
    commit="$(git -C "$ROOT" commit-tree "$tree" $parent -m "AETC checkpoint: $name")"
    git -C "$ROOT" update-ref "refs/aetc/cp/$name" "$commit"
    rm -f "$tmp_index"
    printf '%s\tgit\t%s\t%s\t%s\n' "$name" "refs/aetc/cp/$name" "$commit" "$ts" >> "$REGISTRY"
    info "检查点已创建: $name = $commit (git ref，不含audit/)"
  else
    local tarball="$CKP_DIR/$name.tar.gz"
    tar -czf "$tarball" -C "$ROOT" \
        --exclude='./audit' --exclude='./.git' --exclude='./node_modules' . 2>/dev/null
    printf '%s\ttar\t%s\t%s\t%s\n' "$name" "$name.tar.gz" "$(sha256sum "$tarball" | cut -d' ' -f1)" "$ts" >> "$REGISTRY"
    info "检查点已创建: $name = $name.tar.gz (tar，不含audit/)"
  fi
  log_event "$name" "checkpoint" "${1:-}"
  echo "$note" | grep -q '^$' || printf '\tnote: %s\n' "$note" >> "$REGISTRY" || true
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
  printf '%-28s %-5s %-30s %s\n' "NAME" "TYPE" "TARGET" "TIME"
  awk -F'\t' 'NF>=5{printf "%-28s %-5s %-30s %s\n",$1,$2,substr($3,1,30),$5}' "$REGISTRY"
  if is_git; then
    git -C "$ROOT" for-each-ref --format='  git-ref: %(refname:short) %(objectname:short)' refs/aetc/cp || true
  fi
}

case "${1:-}" in
  create) shift; cmd_create "$@" ;;
  restore) shift; cmd_restore "$@" ;;
  list) shift; cmd_list "$@" ;;
  *) echo "用法: checkpoint.sh create|restore|list"; exit 2 ;;
esac
