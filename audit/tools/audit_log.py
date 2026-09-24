#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AETC 统一留痕写入器 — 将执行事件写入 audit/traces/<session>.jsonl（哈希链防篡改）

用法:
  python3 audit/tools/audit_log.py EVENT --phase coding --summary "..." \
      [--detail "..."] [--risk R1] [--artifacts "a.py,b.py"] [--skill "..."] \
      [--decision "..."] [--actor agent] [--session s-xxx] [--project-root PATH]

事件类型(13): TASK_OPEN PLAN_COMMIT STEP_DONE DECISION SKILL_USE RISK_PRE
  RISK_POST CHECKPOINT GATE_REQUEST GATE_RESULT BLOCKED CORRECTION TASK_CLOSE
阶段(7): requirements design coding testing release ops retro
风险(4): R0 R1 R2 R3
"""
import argparse, hashlib, json, os, re, sys
from datetime import datetime, timezone

SCHEMA = "aetc/1.0"
EVENTS = {"TASK_OPEN", "PLAN_COMMIT", "STEP_DONE", "DECISION", "SKILL_USE",
          "RISK_PRE", "RISK_POST", "CHECKPOINT", "GATE_REQUEST", "GATE_RESULT",
          "BLOCKED", "CORRECTION", "TASK_CLOSE"}
PHASES = {"requirements", "design", "coding", "testing", "release", "ops", "retro"}
RISKS = {"R0", "R1", "R2", "R3"}


def find_project_root(explicit=None):
    """按 显式指定 > 环境变量 > 从cwd向上找 audit/ 目录 的顺序定位项目根。"""
    if explicit:
        return os.path.abspath(explicit)
    env = os.environ.get("AETC_PROJECT_ROOT")
    if env:
        return os.path.abspath(env)
    d = os.getcwd()
    for _ in range(20):
        if os.path.isdir(os.path.join(d, "audit")):
            return d
        parent = os.path.dirname(d)
        if parent == d:
            break
        d = parent
    return os.getcwd()


def canon(record):
    """与 audit_check.py 一致的规范化序列化（排序键、非ASCII原样）。"""
    return json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def last_line(path):
    prev_hash, seq = None, 0
    if os.path.exists(path):
        with open(path, "rb") as f:
            f.seek(0, os.SEEK_END)
            end = f.tell()
            data = b""
            while end > 0 and data.count(b"\n") < 2:
                step = min(4096, end)
                end -= step
                f.seek(end)
                data = f.read() + data
            lines = [l for l in data.decode("utf-8", "replace").splitlines() if l.strip()]
            if lines:
                try:
                    rec = json.loads(lines[-1])
                    prev_hash = rec.get("hash")
                    seq = int(rec.get("seq", 0))
                except Exception:
                    pass
    return prev_hash, seq


def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("event", help="事件类型（13类之一）")
    ap.add_argument("--phase", default=None, help="阶段 requirements|design|coding|testing|release|ops|retro")
    ap.add_argument("--summary", required=True, help="一句话摘要（必填）")
    ap.add_argument("--detail", default=None, help="详情：分析/技能/执行 三段（TRC-005）")
    ap.add_argument("--risk", default=None, help="风险级别 R0~R3")
    ap.add_argument("--artifacts", default=None, help="涉及文件，逗号分隔")
    ap.add_argument("--skill", default=None, help="使用的技能/工具/子Agent")
    ap.add_argument("--decision", default=None, help="决策内容（DECISION/GATE_RESULT 用）")
    ap.add_argument("--actor", default="agent", help="agent|human|tool")
    ap.add_argument("--session", default=None, help="会话ID（默认取 AETC_SESSION 或自动生成）")
    ap.add_argument("--ref", default=None, help="关联事件seq（如 RISK_POST 关联 RISK_PRE）")
    ap.add_argument("--project-root", default=None, help="项目根目录")
    args = ap.parse_args()

    event = args.event.strip().upper()
    if event not in EVENTS:
        sys.stderr.write("[AETC] 非法事件类型: %s（合法：%s）\n" % (args.event, " ".join(sorted(EVENTS))))
        sys.exit(2)
    if not args.summary or not args.summary.strip():
        sys.stderr.write("[AETC] summary 不能为空\n")
        sys.exit(2)
    phase = (args.phase or "").strip().lower() or None
    if phase and phase not in PHASES:
        sys.stderr.write("[AETC] 非法阶段: %s（合法：%s）\n" % (args.phase, " ".join(sorted(PHASES))))
        sys.exit(2)
    risk = (args.risk or "").strip().upper() or None
    if risk and risk not in RISKS:
        sys.stderr.write("[AETC] 非法风险级别: %s（合法：R0 R1 R2 R3）\n" % args.risk)
        sys.exit(2)

    root = find_project_root(args.project_root)
    traces_dir = os.path.join(root, "audit", "traces")
    os.makedirs(traces_dir, exist_ok=True)
    session = args.session or os.environ.get("AETC_SESSION") or (
        "s-" + datetime.now().strftime("%Y%m%d-%H%M%S"))
    if not re.match(r"^[A-Za-z0-9._-]+$", session):
        sys.stderr.write("[AETC] 会话ID含非法字符\n")
        sys.exit(2)

    path = os.path.join(traces_dir, session + ".jsonl")
    prev_hash, seq = last_line(path)
    ts = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")

    rec = {"schema": SCHEMA, "seq": seq + 1, "session": session, "ts": ts,
           "actor": args.actor or "agent", "event": event, "summary": args.summary.strip()}
    if phase:
        rec["phase"] = phase
    if args.detail:
        rec["detail"] = args.detail.strip()
    if risk:
        rec["risk"] = risk
    if args.artifacts:
        rec["artifacts"] = [a.strip() for a in args.artifacts.split(",") if a.strip()]
    if args.skill:
        rec["skill"] = args.skill
    if args.decision:
        rec["decision"] = args.decision.strip()
    if args.ref:
        try:
            rec["ref"] = int(args.ref)
        except ValueError:
            rec["ref"] = args.ref
    rec["prev_hash"] = prev_hash
    rec["hash"] = hashlib.sha256(canon(rec).encode("utf-8")).hexdigest()

    with open(path, "a", encoding="utf-8") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    print("[AETC] %s seq=%d session=%s -> %s" % (event, rec["seq"], session, path))


if __name__ == "__main__":
    main()
