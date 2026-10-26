#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""atlas_check.py — ATLAS 规范本体门禁校验（G1–G5）
用法：python3 .standards/atlas/scripts/atlas_check.py [--root <项目根>]（默认取本脚本上两级）
校验：G1 入口与规则文件存在 | G2 配置模板完整 | G3 四份数据契约可解析且必填字段齐全
     | G4 关键协议条款在场（D级矩阵/白名单/四要素/EXPIRED语义） | G5 与 AETC 联动声明在场
退出码 0=PASS 1=FAIL
"""
import json
import os
import sys

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
    a = os.path.join(root, ".standards", "atlas")

    # G1 入口与规则文件
    for rel in [".standards/ATLAS.md", ".standards/atlas/ATLAS-RULES.md"]:
        check("G1", f"入口/规则 {rel}", os.path.isfile(os.path.join(root, rel)))

    # G2 配置模板
    cfg = os.path.join(a, "config.yaml")
    ok = os.path.isfile(cfg)
    keys = []
    if ok:
        text = open(cfg, encoding="utf-8").read()
        keys = [k for k in ["endpoint", "agent_id", "secret_ref", "l3_sample_rate",
                            "ttl_minutes", "expired_policy", "whitelist", "redaction"] if k in text]
    check("G2", "config.yaml 模板完整（含关键配置节）", ok and len(keys) >= 7,
          f"缺: {set(['endpoint','agent_id','secret_ref','l3_sample_rate','ttl_minutes','expired_policy','whitelist','redaction']) - set(keys)}")

    # G3 数据契约
    required = {
        "trace-event.schema.json": ["event_id", "event_type", "severity", "timestamp", "agent", "context", "event_hash"],
        "change-report.schema.json": ["report_id", "intent", "risk_level", "changes", "diff_ref", "impact", "evidence", "rollback", "commit"],
        "decision-ticket.schema.json": ["decision_id", "level", "question", "options", "ttl_minutes", "status"],
        "command.schema.json": ["command_id", "command_type", "scope", "payload", "expires_at", "signature"],
    }
    for fn, req in required.items():
        p = os.path.join(a, "schemas", fn)
        ok = os.path.isfile(p)
        miss = []
        if ok:
            try:
                sch = json.load(open(p, encoding="utf-8"))
                props = list(sch.get("properties", {}).keys())
                miss = [k for k in req if k not in props]
                ok = not miss
            except Exception as e:  # noqa: BLE001
                ok = False
                miss = [f"JSON解析失败: {e}"]
        check("G3", f"数据契约 {fn}", ok, ("缺字段: " + ",".join(miss)) if miss else "")

    # G4 关键协议条款在场
    rules = os.path.join(a, "ATLAS-RULES.md")
    if os.path.isfile(rules):
        text = open(rules, encoding="utf-8").read()
        for kw, desc in [("D0", "决策分级下界"), ("D3", "决策分级上界"), ("task.dispatch", "指令白五类"),
                         ("task.rerun", "指令白五类"), ("policy.update", "指令白五类"),
                         ("四要素", "指令本地校验"), ("fail-safe", "EXPIRED 默认拒绝"),
                         ("A1", "验收清单"), ("A8", "验收清单"), ("HMAC-SHA256", "鉴权算法"),
                         ("prev_event_hash", "哈希链字段")]:
            check("G4", f"协议条款在场：{desc}", kw in text)
    else:
        check("G4", "ATLAS-RULES.md 存在", False)

    # G5 与 AETC 联动声明（层次分界：本地账本 vs 平台账本）
    entry = os.path.join(root, ".standards/ATLAS.md")
    if os.path.isfile(entry):
        text = open(entry, encoding="utf-8").read()
        ok = ("AETC" in text) and ("本地账本" in text or "平台账本" in text)
        check("G5", ".standards/ATLAS.md 声明与 AETC 层次分界（本地账本/平台账本）", ok)
    else:
        check("G5", ".standards/ATLAS.md 存在", False)

    print("ATLAS 规范本体校验（atlas_check.py G1–G5）")
    print("\n".join(report))
    print(f"\n结果：{'PASS ✅' if errors == 0 else 'FAIL ❌'} | "
          f"{len([r for r in report if '[PASS]' in r])} 项通过 / {errors} 项失败")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
