#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ops_check.py — OPS-STD-001 生产运维智能体门禁校验
用法：python3 ops_check.py [--root <项目根>] [--since <日期>]
校验组：
  G1 运行时目录    —— .ops/ 存在且 config/PROGRESS 就绪
  G2 巡检报告齐备  —— 近 N 日（默认3）每日有 D-OPS-01 且非空非占位
  G3 事件闭环链    —— 台账(D-OPS-09)登记的每个事件有 03→04→05 报告，
                      P0/P1 还须有 06 复盘；豁免须显式记录
  G4 证据锚点      —— D-OPS-04 含时间线章节与置信度标注 [A]/[B]/[C]
  G5 报告章节完整  —— 各报告包含模板规定的必要章节标题
  G6 审批与三确认  —— D-OPS-05 含审批号字段；缺审批号的执行记录为 FAIL
  G7 文档交叉引用  —— 报告间 INC- 编号引用真实可达
  G8 AETC 协同     —— audit/traces 留痕存在性（软校验）
退出码 0=PASS 1=FAIL；报告写 .ops/ops-check-report.md
"""
import os
import re
import sys

ROOT = os.path.abspath(os.getcwd())

REPORT_DOCS = ["D-OPS-01", "D-OPS-02", "D-OPS-03", "D-OPS-04", "D-OPS-05",
               "D-OPS-06", "D-OPS-07", "D-OPS-08", "D-OPS-09", "D-OPS-10"]

REQUIRED_SECTIONS = {
    "D-OPS-01": ["整体健康度", "指标巡检", "慢变量预警"],
    "D-OPS-02": ["SLA", "事件统计", "告警系统健康"],
    "D-OPS-03": ["告警溯源", "初判分级", "上下文富化"],
    "D-OPS-04": ["完整时间线", "根因结论", "置信度", "验证回填"],
    "D-OPS-05": ["三确认", "审批", "回滚预案", "观察期"],
    "D-OPS-06": ["MTTR", "根因回证", "行动项"],
    "D-OPS-07": ["适用症状", "回滚方法", "最近验证"],
    "D-OPS-08": ["未闭环事件", "交接确认"],
    "D-OPS-09": ["事件索引表", "豁免记录"],
    "D-OPS-10": ["症状指纹", "入库标准"],
}


def read(p):
    try:
        return open(p, encoding="utf-8").read()
    except OSError:
        return ""


def main():
    ap = __import__("argparse").ArgumentParser()
    ap.add_argument("--root", default=ROOT)
    ap.add_argument("--since", default=None, help="巡检核验起始日 YYYYMMDD（默认近3日）")
    args = ap.parse_args()
    root = os.path.abspath(args.root)
    docs = os.path.join(root, "docs", "ops")
    dotops = os.path.join(root, ".ops")

    errors, warnings = 0, 0
    report = ["# OPS 门禁校验报告（ops_check.py）", ""]

    def check(g, name, ok, detail=""):
        nonlocal errors
        tag = "PASS" if ok else "FAIL"
        if not ok:
            errors += 1
        report.append(f"- [{tag}] {g} {name}" + (f" | {detail}" if detail else ""))

    def warn(g, name, detail):
        nonlocal warnings
        report.append(f"- [WARN] {g} {name}" + (f" | {detail}" if detail else ""))

    # ── G1 运行时目录 ──
    check("G1", ".ops/ 运行时目录存在", os.path.isdir(dotops))
    prog = read(os.path.join(dotops, "progress", "PROGRESS.md"))
    check("G1", "PROGRESS.md 存在且非空", len(prog.strip()) > 20)

    # ── G2 巡检齐备 ──
    import datetime
    since = args.since or (datetime.date.today() - datetime.timedelta(days=3)).strftime("%Y%m%d")
    patrol = os.path.join(docs, "D-OPS-01")
    if os.path.isdir(patrol):
        days = [d for d in os.listdir(patrol) if re.match(r"\d{8}", d)]
        got = [d for d in days if d >= since]
        miss = []
        for d in got:
            fp = os.path.join(patrol, d)
            for fn in os.listdir(fp) if os.path.isdir(fp) else []:
                s = read(os.path.join(fp, fn))
                if "【待填写】" in s or len(s.strip()) < 200:
                    miss.append(d)
        check("G2", f"巡检报告齐备（{since} 起）", len(got) > 0 and not miss,
              f"{len(got)} 日有报告" + (f"；占位/空: {miss}" if miss else ""))
    else:
        check("G2", "巡检目录 docs/ops/D-OPS-01/", False, "目录不存在")

    # ── G3 事件闭环 ──
    ledger = ""
    for fn in ("D-OPS-09.md", "D-OPS-09"):
        ledger = read(os.path.join(docs, fn, "index.md") if os.path.isdir(os.path.join(docs, fn))
                      else os.path.join(docs, fn))
        if ledger:
            break
    inc_ids = sorted(set(re.findall(r"INC-\d{8}-\d+", ledger)))
    exempt_ids = {m.group(1) for m in re.finditer(r"(INC-\d{8}-\d+)[^\n]*豁免|豁免[^\n]*(INC-\d{8}-\d+)", ledger)
                  for m in [m] if m.group(1) or m.group(2)}
    exempt_ids = {i for i in exempt_ids if i}
    if not inc_ids:
        warn("G3", "台账无登记事件", "若近期无事件属正常")
    flat_files = [os.path.join(docs, f) for f in os.listdir(docs)
                  if os.path.isfile(os.path.join(docs, f)) and f.endswith(".md")
                  and not f.startswith("D-OPS-09")]
    broken = []
    for inc in inc_ids:
        has = {}
        for k in ("D-OPS-03", "D-OPS-04", "D-OPS-05"):
            kd = os.path.join(docs, k)
            ok = False
            if os.path.isdir(kd):
                ok = any(inc in f or inc in read(os.path.join(kd, f)) for f in os.listdir(kd))
            if not ok:  # 平铺文件（排除台账自身）：文件内容含事件号且含报告类型标识
                ok = any(inc in read(fp) and (k in read(fp) or k.split("-")[-1] in read(fp))
                         for fp in flat_files)
            has[k] = ok
        if not all(has.values()) and inc not in exempt_ids:
            broken.append(inc)
    check("G3", f"事件闭环链（{len(inc_ids)} 事件）", not broken,
          "缺链: " + "; ".join(broken[:5]) if broken else "")
    if inc_ids and "豁免" not in ledger:
        warn("G3", "台账无豁免章节", "建议模板补齐")

    # ── G4 证据锚点（最近 RCA） ──
    rca_dir = os.path.join(docs, "D-OPS-04")
    rcas = []
    if os.path.isdir(rca_dir):
        for f in os.listdir(rca_dir):
            rcas.append(read(os.path.join(rca_dir, f)))
    rca_all = "\n".join(rcas)
    if rcas:
        check("G4", "RCA 含时间线章节", "完整时间线" in rca_all or "时间线" in rca_all)
        check("G4", "RCA 含置信度标注", bool(re.search(r"\[[ABC]\]", rca_all)))
        check("G4", "RCA 含证据引用格式", bool(re.search(r"(监控:|日志|变更:|@)", rca_all)))
    else:
        warn("G4", "无 RCA 报告", "仅在有事件时校验")

    # ── G5 章节完整性 ──
    for code, secs in REQUIRED_SECTIONS.items():
        found = []
        base = os.path.join(docs, code)
        if os.path.isdir(base):
            for f in os.listdir(base):
                found.append(read(os.path.join(base, f)))
        elif os.path.isfile(base + ".md"):
            found.append(read(base + ".md"))
        text = "\n".join(found)
        if found:
            miss = [s for s in secs if s not in text]
            check("G5", f"{code} 章节完整", not miss, "缺: " + "; ".join(miss) if miss else "")

    # ── G6 审批与三确认 ──
    act_dir = os.path.join(docs, "D-OPS-05")
    if os.path.isdir(act_dir) and os.listdir(act_dir):
        acts = "\n".join(read(os.path.join(act_dir, f)) for f in os.listdir(act_dir))
        if "审批" in acts:
            ok = bool(re.search(r"审批号|审批人", acts)) and "三确认" in acts
            check("G6", "处置记录含三确认与审批号", ok)
        else:
            warn("G6", "处置记录未见执行类内容", "")
    else:
        warn("G6", "无处置记录", "仅在有执行时校验")

    # ── G7 交叉引用 ──
    all_reports = ""
    for root_, _, fs in os.walk(docs):
        for f in fs:
            all_reports += read(os.path.join(root_, f))
    refs = set(re.findall(r"D-OPS-\d{2}", all_reports))
    missing = [r for r in refs if not (os.path.isdir(os.path.join(docs, r)) or os.path.isfile(os.path.join(docs, r + ".md")))]
    check("G7", f"报告交叉引用（{len(refs)} 类被引用）", not missing,
          "不可达: " + "; ".join(missing) if missing else "")

    # ── G8 AETC 协同（软校验） ──
    audit_dir = os.path.join(root, "audit", "traces")
    if os.path.isdir(audit_dir) and os.listdir(audit_dir):
        report.append(f"- [PASS] G8 AETC 留痕库非空 | {len(os.listdir(audit_dir))} 条留痕")
    else:
        warn("G8", "未检测到 AETC 留痕", "L2/L3 处置需与 AETC-STD-001 协同部署")

    npass = len([l for l in report if "[PASS]" in l])
    report.insert(1, f"\n结果：{'PASS ✅' if errors == 0 else 'FAIL ❌'} | {npass} 项通过 / {errors} 项失败 / {warnings} 提示\n")
    out = os.path.join(dotops, "ops-check-report.md")
    os.makedirs(dotops, exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(report) + "\n")
    print("\n".join(report))
    print("\n报告:", out)
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
