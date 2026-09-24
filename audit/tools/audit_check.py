#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AETC 留痕校验器 — 校验 audit/traces/ 的哈希链完整性、字段合规性、事件配对与指标

用法:
  python3 audit/tools/audit_check.py [--project-root PATH] [--json]
退出码: 0=无ERROR（允许WARN） 1=存在ERROR
"""
import argparse, glob, hashlib, json, os, sys

SCHEMA = "aetc/1.0"
EVENTS = {"TASK_OPEN", "PLAN_COMMIT", "STEP_DONE", "DECISION", "SKILL_USE",
          "RISK_PRE", "RISK_POST", "CHECKPOINT", "GATE_REQUEST", "GATE_RESULT",
          "BLOCKED", "CORRECTION", "TASK_CLOSE"}
PHASES = {"requirements", "design", "coding", "testing", "release", "ops", "retro"}
RISKS = {"R0", "R1", "R2", "R3"}
ACTORS = {"agent", "human", "tool"}
GATES = {"G1": "需求确认", "G2": "设计评审", "G3": "编码自审", "G4": "测试通过", "G5": "发布审批"}


def canon(record):
    return json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def check_file(path):
    """返回 (errors, warnings, stats)"""
    errors, warnings = [], []
    recs = []
    with open(path, encoding="utf-8") as f:
        for ln, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                recs.append((ln, json.loads(line)))
            except json.JSONDecodeError as e:
                errors.append("%s:%d JSON解析失败: %s" % (os.path.basename(path), ln, e))
    if not recs:
        return errors, warnings, None

    prev_hash = None
    prev_seq = 0
    n = len(recs)
    counts = {}
    risk_counts = {}
    opened = closed = False
    open_risk_pre = {}   # seq -> rec (RISK_PRE)
    open_gate = {}       # seq -> rec (GATE_REQUEST)
    unpaired_pre, unpaired_gate = [], []

    for ln, rec in recs:
        tag = "%s:%d(seq=%s)" % (os.path.basename(path), ln, rec.get("seq", "?"))
        # 必填字段
        for k in ("schema", "seq", "session", "ts", "actor", "event", "summary", "prev_hash", "hash"):
            if k not in rec:
                errors.append("%s 缺少字段 %s" % (tag, k))
        if "event" not in rec:
            continue
        ev = rec["event"]
        counts[ev] = counts.get(ev, 0) + 1
        # 枚举校验
        if ev not in EVENTS:
            errors.append("%s 非法事件类型 %s" % (tag, ev))
        if rec.get("schema") != SCHEMA:
            errors.append("%s schema版本不符: %s" % (tag, rec.get("schema")))
        if "phase" in rec and rec["phase"] not in PHASES:
            errors.append("%s 非法阶段 %s" % (tag, rec["phase"]))
        if "risk" in rec and rec["risk"] not in RISKS:
            errors.append("%s 非法风险级别 %s" % (tag, rec["risk"]))
        if rec.get("actor") not in ACTORS:
            errors.append("%s 非法actor %s" % (tag, rec.get("actor")))
        # seq 递增
        try:
            seq = int(rec.get("seq", 0))
            if seq != prev_seq + 1:
                errors.append("%s seq不连续: 期望%d 实际%s" % (tag, prev_seq + 1, seq))
            prev_seq = seq
        except (TypeError, ValueError):
            prev_seq = prev_seq or 0
        # 哈希链
        body = {k: v for k, v in rec.items() if k != "hash"}
        calc = hashlib.sha256(canon(body).encode("utf-8")).hexdigest()
        if rec.get("hash") != calc:
            errors.append("%s 哈希校验失败（记录疑似被篡改）" % tag)
        if rec.get("prev_hash") != prev_hash:
            errors.append("%s prev_hash 与上一条不衔接" % tag)
        prev_hash = rec.get("hash")
        # 事件语义
        if ev == "RISK_PRE":
            open_risk_pre[rec.get("seq")] = rec
        elif ev == "RISK_POST":
            ref = rec.get("ref")
            if ref is not None and ref in open_risk_pre:
                del open_risk_pre[ref]
            elif ref is not None:
                warnings.append("%s RISK_POST 引用了不存在的RISK_PRE seq=%s" % (tag, ref))
        elif ev == "GATE_REQUEST":
            open_gate[rec.get("seq")] = rec
        elif ev == "GATE_RESULT":
            ref = rec.get("ref")
            if ref is not None and ref in open_gate:
                g = open_gate.pop(ref)
                dec = " ".join(str(rec.get(k, "")) for k in ("summary", "detail", "decision"))
                if not any(v in dec for v in ("approved", "rejected", "modified")):
                    warnings.append("%s GATE_RESULT 结论不明确（应含 approved/rejected/modified）" % tag)
                elif "approved" not in dec:
                    # 拒绝/修改后，对应的风险操作不得执行
                    warnings.append("%s 审批结论为 %s（非approved），后续不得执行该风险操作" % (tag, dec[:40]))
            else:
                warnings.append("%s GATE_RESULT 未引用对应GATE_REQUEST(seq=%s)" % (tag, ref))
        if ev == "TASK_OPEN":
            opened = True
        if ev == "TASK_CLOSE":
            closed = True
        # detail 三段
        if ev == "STEP_DONE":
            d = rec.get("detail") or ""
            if d and not any(s in d for s in ("分析", "技能", "执行")):
                warnings.append("%s STEP_DONE.detail 未含 分析/技能/执行 三段（TRC-005）" % tag)
        if "risk" in rec:
            risk_counts[rec["risk"]] = risk_counts.get(rec["risk"], 0) + 1

    unpaired_pre = sorted(open_risk_pre.keys())
    unpaired_gate = sorted(open_gate.keys())
    if unpaired_pre:
        warnings.append("%s 有 %d 个RISK_PRE未配对RISK_POST(seq:%s)" %
                        (os.path.basename(path), len(unpaired_pre), ",".join(map(str, unpaired_pre[:5]))))
    if unpaired_gate:
        warnings.append("%s 有 %d 个GATE_REQUEST未获GATE_RESULT(seq:%s) — 任务可能仍在进行中" %
                        (os.path.basename(path), len(unpaired_gate), ",".join(map(str, unpaired_gate[:5]))))
    if not opened:
        warnings.append("%s 缺少TASK_OPEN（会话不完整）" % os.path.basename(path))
    if not closed:
        warnings.append("%s 缺少TASK_CLOSE（任务未收尾或仍在进行）" % os.path.basename(path))

    # 指标：审批合规率（R2/R3的RISK_PRE 前后是否存在同级别的 GATE_REQUEST→approved GATE_RESULT）
    r23 = [r for _, r in recs if r.get("event") == "RISK_PRE" and r.get("risk") in ("R2", "R3")]
    gated = 0
    for r in r23:
        s = r.get("seq")
        # 找到该 RISK_PRE 的 RISK_POST（引用其seq），确定时间窗 (s, e]
        e = None
        for _, x in recs:
            if x.get("event") == "RISK_POST" and x.get("ref") == s:
                e = x.get("seq")
                break
        ok = False
        for _, x in recs:
            if x.get("event") == "GATE_REQUEST" and x.get("risk") == r.get("risk") \
               and x.get("seq", 0) > s and (e is None or x.get("seq", 0) <= e):
                # 对应 GATE_RESULT 是否 approved
                gseq = x.get("seq")
                for _, y in recs:
                    if y.get("event") == "GATE_RESULT" and y.get("ref") == gseq:
                        dec = " ".join(str(y.get(k, "")) for k in ("summary", "detail", "decision"))
                        if "approved" in dec:
                            ok = True
                        break
                if ok:
                    break
        if ok:
            gated += 1
    stats = {
        "file": os.path.basename(path),
        "records": n,
        "events": counts,
        "risk": risk_counts,
        "r23_gated": "%d/%d" % (gated, len(r23)),
        "warnings": len(warnings),
        "errors": len(errors),
    }
    return errors, warnings, stats


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--project-root", default=None)
    ap.add_argument("--json", action="store_true", help="输出JSON格式结果")
    args = ap.parse_args()

    root = args.project_root or os.environ.get("AETC_PROJECT_ROOT") or os.getcwd()
    traces_dir = os.path.join(root, "audit", "traces")
    files = sorted(glob.glob(os.path.join(traces_dir, "*.jsonl")))
    all_errors, all_warnings, all_stats = [], [], []
    for fp in files:
        e, w, s = check_file(fp)
        all_errors += e
        all_warnings += w
        if s:
            all_stats.append(s)
    # 钩子留痕（原始捕获，不做链校验，仅统计）
    hooks = sorted(glob.glob(os.path.join(traces_dir, "hooks", "*.jsonl")))
    hook_count = 0
    for hp in hooks:
        with open(hp, encoding="utf-8") as f:
            hook_count += sum(1 for l in f if l.strip())

    if args.json:
        print(json.dumps({"sessions": all_stats, "hook_records": hook_count,
                          "errors": all_errors, "warnings": all_warnings},
                         ensure_ascii=False, indent=2))
    else:
        print("AETC 留痕校验报告（规范 AETC-STD-001 V1.0）")
        print("=" * 62)
        if not all_stats:
            print("未发现任何会话留痕文件（audit/traces/*.jsonl）")
        for s in all_stats:
            print("会话 %s：记录 %d 条，事件分布 %s" % (s["file"], s["records"], s["events"]))
            print("  风险分布 %s | R2/R3配审批门 %s | WARN %d | ERROR %d" %
                  (s["risk"], s["r23_gated"], s["warnings"], s["errors"]))
        if hooks:
            print("钩子自动留痕：%d 个文件 / %d 条原始记录（audit/traces/hooks/）" % (len(hooks), hook_count))
        print("-" * 62)
        for w in all_warnings:
            print("[WARN] %s" % w)
        for e in all_errors:
            print("[ERROR] %s" % e)
        print("=" * 62)
        print("结论：%d ERROR / %d WARN %s" %
              (len(all_errors), len(all_warnings),
               "— 链完整，可用于复核" if not all_errors else "— 存在篡改或结构性问题，须人工介入"))
    sys.exit(1 if all_errors else 0)


if __name__ == "__main__":
    main()
