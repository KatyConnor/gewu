#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""rev_check.py — THEMIS 评审审计域本体门禁（V1.9.26 批2 · 同构 aegis_check/handoff_check）
校验：
  G-T1 结构：入口正本/四模板/判型记录在位（判型含独立性等价三件套）
  G-T2 首审：REV-000 四单齐+CAPA 状态合法（planned|in_progress|rechecked|closed）
  G-T3 单据结构：RPT-*.md 必含三值结论+独立性确认；模板契约字段齐
  G-T4 互锁：RPT 引用的 CAPA 单据可达（单据链互锁，对齐 AEGIS trace_ref 精神）
selftest：sandbox 四场景翻转（空报/齐备过/结论缺报/状态非法报）
用法：python3 .standards/themis/scripts/rev_check.py [--root <项目根>] [--selftest] [--metrics]
退出码 0=PASS 1=FAIL（报警语义：列问题不阻断业务，阻断由链上顺序门裁决）
"""
import glob, os, re, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))  # scripts→themis→.standards→根
STATE_LEGAL = {"planned", "in_progress", "rechecked", "closed"}
TRI = ("通过", "有条件通过", "回退")

errors = []


def check(gid, name, ok, detail=""):
    if not ok:
        errors.append(f"[{gid}] {name}{('——' + detail) if detail else ''}")


def readf(p):
    return open(p, encoding="utf-8").read() if os.path.isfile(p) else ""




def metrics(root):
    """§七 度量实采（V1.9.27 批3）：从 themis/records/ 单据统计——不虚报，样本不足如实标。"""
    rec_dir = os.path.join(root, ".standards", "themis", "records")
    recs = glob.glob(os.path.join(rec_dir, "*.md"))
    s_all = "\n".join(readf(p) for p in recs)
    import re as _re
    reviews = len(set(_re.findall(r"RR-\d{3}", s_all)))   # 评审数按 RR 委托单计（REV/RPT/F 为同一链单据）
    sev = {k: len(_re.findall(rf"severity: {k}(?![\d])", s_all)) for k in ("S1", "S2", "S3", "S4")}
    findings = sum(sev.values())
    capa_txt = "\n".join(readf(p) for p in recs if os.path.basename(p).startswith("CAPA-"))  # V1.9.28 修正：闭环计数限 CAPA 单（RPT/REV 的 state: closed 不计）
    capas = len(set(_re.findall(r"CAPA-\d{3}", s_all)))
    closed = capa_txt.count("state: closed")
    one_pass = len(_re.findall(r"result: 通过", s_all))
    viol = s_all.count("independence_check: false")
    small = (findings < 10) or (reviews < 10)
    print("[THEMIS-METRICS] 度量首采口径（单据派生·REV/RPT/FINDING/CAPA）")
    print(f"  评审数（RR 委托单）：{reviews}")
    print(f"  发现分布：S1={sev['S1']} S2={sev['S2']} S3={sev['S3']} S4={sev['S4']}（共 {findings}）")
    print(f"  CAPA：{capas} 项 / closed {closed} / 闭环率 {('%.0f%%' % (100*closed/capas)) if capas else 'n/a'}")
    print(f"  复审一次通过率：{('%.0f%%' % (100*one_pass/capas)) if capas else 'n/a'}（通过 {one_pass}/{capas}）")
    print(f"  独立性违例（红线）：{viol}{' ←红线！' if viol else ''}")
    if small:
        print("  ⚠ 样本量不足（n<10）：仅记录不设基线（棘轮原则——基线待样本累积后设）")
    return 0 if viol == 0 else 1

def verify(root):
    global errors
    errors = []
    th = readf(os.path.join(root, ".standards", "THEMIS.md"))
    # G-T1 结构
    check("G-T1", "入口正本 THEMIS.md 在位（含四路由+独立性语义）",
          "THEMIS-STD-014" in th and "AETC 稽核复核" in th and "独立性" in th)
    for t in ("RR", "FINDING", "RPT", "CAPA"):
        check("G-T1", f"模板 {t}-template.yaml", os.path.isfile(
            os.path.join(root, ".standards", "themis", "templates", f"{t}-template.yaml")))
    check("G-T1", "判型记录 spec-themis.yaml（含独立性等价三件套·V1.9.26）",
          "independence_equivalence" in readf(os.path.join(root, ".standards", "meta", "records", "spec-themis.yaml")))
    # G-T2 首审
    rev0 = readf(os.path.join(root, ".standards", "themis", "records", "REV-000-立项自审.md"))
    check("G-T2", "REV-000 首审四单齐（RR/FINDING/RPT/CAPA 段）",
          all(k in rev0 for k in ("RR-000", "F-", "RPT-000", "CAPA-000")))
    m = re.search(r"state:\s*(\w+)", rev0.split("CAPA-000")[-1]) if "CAPA-000" in rev0 else None
    check("G-T2", "REV-000 复审已闭环（CAPA-000 state=closed·四单契约完整演练）",
          bool(m) and m.group(1) == "closed",
          f"实得 {m.group(1) if m else '未解析'}（V1.9.25 批1 为 planned·批2 复审后须 closed）")
    # G-T3/G-T4 逐单据结构+互锁
    rec_dir = os.path.join(root, ".standards", "themis", "records")
    recs_all = glob.glob(os.path.join(rec_dir, "*.md"))
    rpts = [p for p in recs_all if os.path.basename(p).startswith("RPT-")]
    for p in rpts:
        s = readf(p)
        check("G-T3", f"{os.path.basename(p)} 三值结论在位", any(c in s for c in TRI))
        check("G-T3", f"{os.path.basename(p)} 独立性确认在位", "independence_check" in s or "独立性" in s)
        for c in set(re.findall(r"CAPA-\w+", s)):
            check("G-T4", f"{os.path.basename(p)} 引用 {c} 可达（单据链互锁）",
                  any(c in readf(q) for q in recs_all))
    # 模板字段契约
    rpt_t = readf(os.path.join(root, ".standards", "themis", "templates", "RPT-template.yaml"))
    check("G-T3", "RPT 模板契约字段（三值结论+conditions+state）",
          all(c in rpt_t for c in TRI) and "conditions" in rpt_t and "state:" in rpt_t)
    capa_t = readf(os.path.join(root, ".standards", "themis", "templates", "CAPA-template.yaml"))
    check("G-T3", "CAPA 模板契约字段（owner/due/verify_method/state）",
          all(c in capa_t for c in ("owner:", "due:", "verify_method:", "state:")))
    print(f"[REV-CHECK] THEMIS 本体 @ {root}")
    for e in errors:
        print(f"  ✗ {e}")
    print(f"评审域门禁：{'FAIL（' + str(len(errors)) + ' 项）' if errors else 'PASS'}")
    return 0 if not errors else 1


def selftest():
    """四场景翻转：空报 FAIL / 齐备 PASS / RPT 缺结论 FAIL / CAPA 状态非法 FAIL"""
    base = tempfile.mkdtemp()
    std = os.path.join(base, ".standards")
    for dp, dn, fn in os.walk(os.path.join(ROOT, ".standards", "themis")):
        if "scripts" in dp:
            continue
        for f in fn:
            rel = os.path.relpath(os.path.join(dp, f), os.path.join(ROOT, ".standards", "themis"))
            dst = os.path.join(std, "themis", rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            open(dst, "w", encoding="utf-8").write(open(os.path.join(dp, f), encoding="utf-8").read())
    os.makedirs(os.path.join(std, "meta", "records"), exist_ok=True)
    open(os.path.join(std, "meta", "records", "spec-themis.yaml"), "w", encoding="utf-8").write(
        open(os.path.join(ROOT, ".standards", "meta", "records", "spec-themis.yaml"), encoding="utf-8").read())
    open(os.path.join(std, "THEMIS.md"), "w", encoding="utf-8").write(
        open(os.path.join(ROOT, ".standards", "THEMIS.md"), encoding="utf-8").read())
    rev0 = open(os.path.join(std, "themis", "records", "REV-000-立项自审.md"), encoding="utf-8").read().replace(
        "state: planned", "state: closed")
    open(os.path.join(std, "themis", "records", "REV-000-立项自审.md"), "w", encoding="utf-8").write(rev0)
    fails = []
    if verify(tempfile.mkdtemp()) == 0:
        fails.append("空项目期望 FAIL 实得 PASS")
    if verify(base) != 0:
        fails.append(f"齐备沙箱期望 PASS 实得 FAIL：{errors[:2]}")
    p3 = os.path.join(std, "themis", "records", "RPT-001-x.md")
    open(p3, "w", encoding="utf-8").write("conclusion: 随便\nindependence_check: true\n")
    if verify(base) == 0:
        fails.append("RPT 缺三值结论期望 FAIL 实得 PASS")
    os.remove(p3)
    rev_bad = rev0.replace("state: closed", "state: done")
    open(os.path.join(std, "themis", "records", "REV-000-立项自审.md"), "w", encoding="utf-8").write(rev_bad)
    if verify(base) == 0:
        fails.append("CAPA 状态 done（非法）期望 FAIL 实得 PASS")
    open(os.path.join(std, "themis", "records", "REV-000-立项自审.md"), "w", encoding="utf-8").write(rev0)
    print(f"selftest：评审域门禁 {'FAIL' if fails else 'PASS（4 场景：空报/齐备过/结论缺报/状态非法报）'}")
    for x in fails:
        print(f"  ✗ [selftest] {x}")
    return 1 if fails else 0


if __name__ == "__main__":
    a = sys.argv[1:]
    if "--selftest" in a:
        sys.exit(selftest())
    if "--metrics" in a:
        root = os.path.abspath(a[a.index("--root") + 1]) if "--root" in a else ROOT
        sys.exit(metrics(root))
    root = os.path.abspath(a[a.index("--root") + 1]) if "--root" in a else ROOT
    sys.exit(verify(root))
