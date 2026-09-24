#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AETC 会话报告生成器 — 把 audit/traces/<session>.jsonl 渲染为人工可读的复核底稿

用法:
  python3 audit/tools/audit_report.py <session-id> [--project-root PATH]
  python3 audit/tools/audit_report.py --all [--project-root PATH]
输出: audit/reports/<session-id>-report.md
"""
import argparse, glob, json, os, sys

GATE_NAMES = {"G1": "需求确认", "G2": "设计评审", "G3": "编码自审",
              "G4": "测试通过", "G5": "发布审批"}
RISK_DESC = {"R0": "只读", "R1": "常规写", "R2": "敏感写", "R3": "危急"}


def load(path):
    recs = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    recs.append(json.loads(line))
                except json.JSONDecodeError:
                    pass
    return recs


def cut(s, n=64):
    s = (s or "").replace("\n", " ")
    return s if len(s) <= n else s[:n - 1] + "…"


def render(session, recs):
    if not recs:
        return None
    phases = sorted({r.get("phase", "-") for r in recs if r.get("phase")})
    t0, t1 = recs[0].get("ts", "?"), recs[-1].get("ts", "?")
    risks = {}
    for r in recs:
        if r.get("risk"):
            risks[r["risk"]] = risks.get(r["risk"], 0) + 1
    task_open = next((r for r in recs if r.get("event") == "TASK_OPEN"), None)

    L = []
    L.append("# 会话复核底稿：%s" % session)
    L.append("")
    L.append("> 由 audit_report.py 依据 audit/traces/%s.jsonl 生成，供人工复核与复盘使用。" % session)
    L.append("")
    L.append("## 一、概览")
    L.append("")
    L.append("| 项 | 值 |")
    L.append("|----|----|")
    L.append("| 会话ID | %s |" % session)
    L.append("| 时间范围 | %s ~ %s |" % (t0, t1))
    L.append("| 涉及阶段 | %s |" % ("、".join(phases) or "-"))
    L.append("| 记录总数 | %d |" % len(recs))
    L.append("| 风险分布 | %s |" % ("、".join("%s×%d" % (k, risks[k]) for k in sorted(risks)) or "无标记"))
    L.append("| 任务描述 | %s |" % cut(task_open.get("summary", "-"), 80) if task_open else "| 任务描述 | - |")
    L.append("")

    L.append("## 二、时间线")
    L.append("")
    L.append("| seq | 时间 | 阶段 | 事件 | 风险 | 摘要 |")
    L.append("|-----|------|------|------|------|------|")
    for r in recs:
        ts = str(r.get("ts", ""))[11:19]
        L.append("| %s | %s | %s | %s | %s | %s |" % (
            r.get("seq", "?"), ts, r.get("phase", "-"), r.get("event", "?"),
            r.get("risk", "-"), cut(r.get("summary", ""), 52).replace("|", "\\|")))
    L.append("")

    decisions = [r for r in recs if r.get("event") in ("DECISION",)]
    if decisions:
        L.append("## 三、决策链")
        L.append("")
        L.append("| seq | 决策点 | 决策内容 |")
        L.append("|-----|--------|----------|")
        for r in decisions:
            L.append("| %s | %s | %s |" % (r.get("seq", "?"),
                                           cut(r.get("summary", ""), 40).replace("|", "\\|"),
                                           cut(r.get("decision", r.get("detail", "")), 60).replace("|", "\\|")))
        L.append("")

    risk_ops = [r for r in recs if r.get("event") in ("RISK_PRE", "RISK_POST")]
    if risk_ops:
        L.append("## 四、风险操作（R2/R3 重点复核区）")
        L.append("")
        L.append("| seq | 事件 | 风险 | 摘要 |")
        L.append("|-----|------|------|------|")
        for r in risk_ops:
            L.append("| %s | %s | %s | %s |" % (r.get("seq", "?"), r.get("event"),
                                               r.get("risk", "-"),
                                               cut(r.get("summary", ""), 56).replace("|", "\\|")))
        L.append("")

    gates = [r for r in recs if r.get("event") in ("GATE_REQUEST", "GATE_RESULT")]
    if gates:
        L.append("## 五、审批门记录")
        L.append("")
        L.append("| seq | 事件 | 请求/结论 | 摘要 |")
        L.append("|-----|------|-----------|------|")
        for r in gates:
            L.append("| %s | %s | %s | %s |" % (r.get("seq", "?"), r.get("event"),
                                               cut(r.get("decision", "-"), 30).replace("|", "\\|"),
                                               cut(r.get("summary", ""), 46).replace("|", "\\|")))
        L.append("")

    blocks = [r for r in recs if r.get("event") in ("BLOCKED", "CORRECTION")]
    if blocks:
        L.append("## 六、阻塞与纠偏")
        L.append("")
        L.append("| seq | 事件 | 摘要 |")
        L.append("|-----|------|------|")
        for r in blocks:
            L.append("| %s | %s | %s |" % (r.get("seq", "?"), r.get("event"),
                                           cut(r.get("summary", ""), 60).replace("|", "\\|")))
        L.append("")

    closes = [r for r in recs if r.get("event") == "TASK_CLOSE"]
    L.append("## 七、收尾自检（Agent 声明）")
    L.append("")
    for r in closes:
        L.append("- **TASK_CLOSE(seq %s)**：%s" % (r.get("seq", "?"), r.get("summary", "-")))
        if r.get("detail"):
            L.append("")
            L.append("  %s" % cut(r.get("detail"), 300))
    if not closes:
        L.append("- ⚠ 本会话尚未写入 TASK_CLOSE（任务未收尾或中断）")
    L.append("")
    L.append("## 八、复核提示")
    L.append("")
    L.append("- 逐条核对第四节风险操作是否已按 RSK-003/004 走完检查点与审批。")
    L.append("- 决策链抽检：任选两条 DECISION 验证理由与实际变更一致。")
    L.append("- 运行 `python3 audit/tools/audit_check.py` 确认哈希链无 ERROR 后再签字。")
    L.append("- 复核结论按 review-checklist.md 模板归档至 audit/reviews/。")
    L.append("")
    return "\n".join(L)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("session", nargs="?", default=None, help="会话ID（省略时用 --all）")
    ap.add_argument("--all", action="store_true", help="为全部会话生成报告")
    ap.add_argument("--project-root", default=None)
    args = ap.parse_args()

    root = args.project_root or os.environ.get("AETC_PROJECT_ROOT") or os.getcwd()
    traces_dir = os.path.join(root, "audit", "traces")
    out_dir = os.path.join(root, "audit", "reports")
    os.makedirs(out_dir, exist_ok=True)

    if args.all:
        files = sorted(glob.glob(os.path.join(traces_dir, "*.jsonl")))
    elif args.session:
        files = [os.path.join(traces_dir, args.session + ".jsonl")]
    else:
        ap.print_help()
        sys.exit(2)

    made = 0
    for fp in files:
        if not os.path.exists(fp):
            print("[AETC] 未找到会话文件: %s" % fp)
            continue
        session = os.path.splitext(os.path.basename(fp))[0]
        md = render(session, load(fp))
        if md is None:
            continue
        out = os.path.join(out_dir, session + "-report.md")
        with open(out, "w", encoding="utf-8") as f:
            f.write(md)
        print("[AETC] 报告已生成: %s" % out)
        made += 1
    if not made:
        print("[AETC] 没有可生成的会话报告")


if __name__ == "__main__":
    main()
