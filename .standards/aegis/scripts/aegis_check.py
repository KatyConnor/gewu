#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""aegis_check.py — AEGIS 规范本体门禁校验（G1–G7）· V1.1（2026-10-05）
用法：python3 .standards/aegis/scripts/aegis_check.py [--root <项目根>]（默认取本脚本上两级）
校验：G1 入口/规则/模板存在 | G2 三单模板 YAML 可解析且关键字段齐全
     | G3 护栏引擎自检（双场景演示：违规 BLOCK / 合规 PASS）
     | G4 守则注入八条完整（第 2、3、4、8 条不可删减） | G5 预算表与冻结清单声明在场
     | G6 与 ATLAS/AETC 协作声明在场（GATE→D级工单 / 留痕联动）
     | G7 V1.1 增量在场（双轴定级/状态机/批量拆单/热修回填/ODC 十类/RC 新五字段）
退出码 0=PASS 1=FAIL
"""
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
from _mini_yaml import loads as _yaml_loads, MiniYAMLError

errors = 0
report = []


def check(gid, name, ok, detail=""):
    global errors
    status = "PASS" if ok else "FAIL"
    if not ok:
        errors += 1
    report.append(f"- [{status}] {gid} {name}" + (f" | {detail}" if detail else ""))


def main():
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
    if "--root" in sys.argv:
        root = os.path.abspath(sys.argv[sys.argv.index("--root") + 1])
    a = os.path.join(root, ".standards", "aegis")

    # G1 入口/规则/模板
    for rel in [".standards/AEGIS.md", ".standards/aegis/AEGIS-RULES.md",
                ".standards/aegis/templates/RC-template.yaml",
                ".standards/aegis/templates/FP-template.yaml",
                ".standards/aegis/templates/VR-template.yaml",
                ".standards/aegis/guard/aegis_guard.py"]:
        check("G1", f"入口/规则/模板 {rel}", os.path.isfile(os.path.join(root, rel)))

    # G2 三单模板 schema 关键字段
    req = {
"RC-template.yaml": ["issue", "symptom", "chain", "root_cause", "evidence", "impact_scope", "confidence"],
        "FP-template.yaml": ["issue", "risk_level", "root_cause_ref", "changes", "budget", "verification", "rollback"],
        "VR-template.yaml": ["plan_ref", "gates", "diff_vs_plan", "lessons"],
    }
    for fn, keys in req.items():
        p = os.path.join(a, "templates", fn)
        if not os.path.isfile(p):
            check("G2", f"模板 {fn}", False, "文件缺失")
            continue
        try:
            d = _yaml_loads(open(p, encoding="utf-8").read())
            miss = [k for k in keys if k not in d]
            check("G2", f"模板 {fn} 字段齐全", not miss, f"缺: {miss}" if miss else "")
        except (MiniYAMLError, OSError) as e:  # V1.9.28 收窄：解析/读文件错误（其余异常不吞）
            check("G2", f"模板 {fn} YAML 可解析", False, str(e))

    # G3 护栏引擎自检（双场景演示：场景一 BLOCK、场景二 PASS，demo 退出码 0）
    guard = os.path.join(a, "guard", "aegis_guard.py")
    if os.path.isfile(guard):
        try:
            r = subprocess.run([sys.executable, guard, "demo"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=120)
            out = r.stdout + r.stderr
            ok = r.returncode == 0 and "BLOCK" in out and "PASS" in out
            check("G3", "护栏引擎双场景自检（违规 BLOCK / 合规 PASS）", ok,
                  f"exit={r.returncode}" if not ok else "")
        except (MiniYAMLError, OSError) as e:  # V1.9.28 收窄：解析/读文件错误（其余异常不吞）
            check("G3", "护栏引擎自检可运行", False, str(e))
    else:
        check("G3", "aegis_guard.py 存在", False)

    # G4 守则注入八条完整（规则全文中必须完整在场）
    rules = os.path.join(a, "AEGIS-RULES.md")
    if os.path.isfile(rules):
        text = open(rules, encoding="utf-8").read()
        probes = [("红基线前禁止写", "守则第2条"), ("测试文件只读", "守则第3条"),
                  ("冻结清单禁止触碰", "守则第4条"), ("即时披露", "守则第8条"),
                  ("三问链", "根因链方法"), ("安灯升级", "有界重试升级"),
                  ("冻结清单", "护栏机制"), ("L5", "铁律编号"), ("L8", "铁律编号")]
        for kw, desc in probes:
            check("G4", f"规则条款在场：{desc}", kw in text)
    else:
        check("G4", "AEGIS-RULES.md 存在", False)

    # G5 预算表与冻结清单
    entry = os.path.join(root, ".standards/AEGIS.md")
    etext = open(entry, encoding="utf-8").read() if os.path.isfile(entry) else ""
    check("G5", "入口声明 diff 预算（90% 告警/100% 停写）", "90%" in etext or "90%" in text)
    check("G5", "入口声明冻结清单默认值", "冻结清单" in etext)
    for kw, desc in [("S0", "定级下界"), ("S4", "定级上界"), ("P0", "流程起点"), ("P6", "流程终点"),
                     ("三层防线", "防线模型"), ("北极星", "度量指标")]:
        check("G5", f"规则覆盖：{desc}", kw in text)

    # G6 与 ATLAS/AETC 协作声明
    for kw, desc in [("ATLAS", "GATE→ATLAS D级工单"), ("TraceEvent", "事件上报协议"),
                     ("AETC", "全程留痕联动")]:
        check("G6", f"协作声明在场：{desc}", kw in etext)

    # G7 V1.1 增量在场
    rules = open(os.path.join(a, "AEGIS-RULES.md"), encoding="utf-8").read()
    rc = open(os.path.join(a, "templates", "RC-template.yaml"), encoding="utf-8").read()
    for kw in ["双轴定级", "生命周期状态机", "批量受理与拆单", "热修旁路与回填义务", "ODC 正交缺陷分类", "Zeller"]:
        check("G7", f"RULES V1.1 增量 [{kw}]", kw in rules)
    for kw in ["severity:", "priority:", "status:", "defect_type:", "hypotheses:"]:
        check("G7", f"RC V1.1 字段 [{kw}]", kw in rc)
    entry = open(os.path.join(root, ".standards", "AEGIS.md"), encoding="utf-8").read()
    check("G7", "入口 V1.1 批量缺陷入口", "批量缺陷入口" in entry and "V1.1" in entry)

    print("AEGIS 规范本体校验（aegis_check.py G1–G7 · V1.1）")
    print("\n".join(report))

    # G8 V1.2 闭环收口（出口闸+证据互锁）
    check("G8", "三单模板含 trace_ref 证据互锁字段（单据链↔AETC 留痕链）",
          all("trace_ref" in open(os.path.join(a, "templates", fn), encoding="utf-8").read()
              for fn in ("RC-template.yaml", "FP-template.yaml", "VR-template.yaml")))
    check("G8", "出口闸：06/UTSS 独立准出接线（三道门自验证≠独立准出）",
          "独立准出" in etext and "UTSS" in etext)
    print(f"\n结果：{'PASS ✅' if errors == 0 else 'FAIL ❌'} | "
          f"{len([r for r in report if '[PASS]' in r])} 项通过 / {errors} 项失败")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
