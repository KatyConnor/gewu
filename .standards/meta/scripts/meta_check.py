#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""meta_check.py — META 规范本体门禁校验（G1-G6）
用法：python3 .standards/meta/scripts/meta_check.py [--root <项目根>]（默认取本脚本上两级）
校验：G1 入口/规则/模板存在 | G2 判据完备（五判据/三判据/五型/仲裁序在场）
     | G3 判型决策记录表模板 YAML 可解析且字段齐 | G4 记录表含自身判型示范（自指合规）
     | G5 与调度器注册表一致（.standards/META.md 在册且类型正确）
     | G6 判型记录完备性（14 套注册表在册规范均有判型决策记录）
退出码 0=PASS 1=FAIL
"""
import os
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
    m = os.path.join(root, ".standards", "meta")

    # G1 入口/规则/模板
    for rel in [".standards/META.md", ".standards/meta/META-RULES.md",
                ".standards/meta/templates/form-classification-record.yaml"]:
        check("G1", f"入口/规则/模板 {rel}", os.path.isfile(os.path.join(root, rel)))

    # G2 判据完备
    rules = os.path.join(m, "META-RULES.md")
    if os.path.isfile(rules):
        text = open(rules, encoding="utf-8").read()
        for kw, desc in [
            ("义务论", "形态判据·语义"), ("Saltzer", "形态判据·强制（机制分离）"),
            ("fail-closed", "形态判据·后果"), ("Polanyi", "形态判据·知识形态"),
            ("分发机制", "形态判据·分发"), ("应/不应", "助动词测试"),
            ("宜", "助动词测试·推荐级"), ("不变量", "层级判据"),
            ("失败半径", "层级判据"), ("单向棘轮", "层级判据·裁剪方向"),
            ("横切·常驻", "类型模型"), ("条件激活", "类型模型"),
            ("互斥组", "类型模型"), ("叠加约束", "类型模型"), ("质量门型", "类型模型"),
            ("危害 > 强制", "仲裁优先序"), ("S7", "七步checklist"),
            ("晋升", "生命周期"), ("例外频次", "生命周期·校准信号"),
            ("spec-pack", "元技能定位"),
        ]:
            check("G2", f"判据在场：{desc}", kw in text)
    else:
        check("G2", "META-RULES.md 存在", False)

    # G3 记录表模板字段齐
    tpl = os.path.join(m, "templates", "form-classification-record.yaml")
    if os.path.isfile(tpl):
        try:
            d = _yaml_loads(open(tpl, encoding="utf-8").read())
            need = ["content_id", "content_kind", "form_tests", "q1_harm",
                    "q2_shall_verifiable", "q3_multisol", "form", "level_tests",
                    "invariant", "repo_bound", "level", "type_model", "mutex_group",
                    "trigger_face", "gates", "lifecycle", "promotion_watch",
                    "exception_budget", "decided_by", "decided_at", "evidence_refs"]
            missing = []
            flat = dict(d)
            flat.update(d.get("form_tests", {}))
            flat.update(d.get("level_tests", {}))
            flat.update(d.get("lifecycle", {}))
            for k in need:
                if k not in flat:
                    missing.append(k)
            check("G3", "判型决策记录表模板字段齐全", not missing,
                  f"缺: {set(missing)}" if missing else "")
        except MiniYAMLError as e:
            check("G3", "模板 YAML 可解析", False, str(e)[:80])
    else:
        check("G3", "模板存在", False)

    # G4 自指合规（记录表含 _self_example 且 form=spec/level=dual/质量门型）
    if os.path.isfile(tpl):
        try:
            d = _yaml_loads(open(tpl, encoding="utf-8").read())
            se = d.get("_self_example") or {}
            ok = (se.get("form") == "spec" and se.get("level") == "dual"
                  and "质量门型" in str(se.get("type_model", ""))
                  and se.get("form_tests", {}).get("q1_harm") is True)
            check("G4", "自指合规：自身判型记录在场（spec/dual/质量门型）", ok)
        except MiniYAMLError:
            check("G4", "自指记录可解析", False)

    # G6 判型记录完备性（注册表在册规范均有 spec-*.yaml）
    recs_dir = os.path.join(m, "records")
    for slug in ["aetc", "atlas", "lpu", "lr", "ops", "sdlc", "aegis", "cr",
                 "ui", "coding", "meta", "ucss", "utss", "themis"]:
        check("G6", f"判型记录 spec-{slug}.yaml", os.path.isfile(os.path.join(recs_dir, f"spec-{slug}.yaml")))
    check("G6", "存量判型清单在场", os.path.isfile(os.path.join(recs_dir, "存量判型清单V1.7.md")))

    # G5 与调度器注册表一致
    entry = os.path.join(root, ".standards/META.md")
    etext = open(entry, encoding="utf-8").read() if os.path.isfile(entry) else ""
    check("G5", ".standards/META.md 声明质量门型·治理域", "质量门型" in etext and "治理域" in etext)
    check("G5", ".standards/META.md 声明自指合规", "自指合规" in etext)
    disp = os.path.join(root, "AGENTS.md")
    if os.path.isfile(disp):
        dt = open(disp, encoding="utf-8").read()
        check("G5", "调度器注册表含 .standards/META.md 行", ".standards/META.md" in dt and "STD-META-001" in dt)
    else:
        check("G5", "调度器 AGENTS.md 存在", False)

    print("META 规范本体校验（meta_check.py G1-G5）")
    # G7 判型记录字段全验（V1.8.17 · P2 收编：分级 schema+历史别名容忍——见 META.md §六）
    _core = ["content_id", "version", "decided_by", "decided_at",
             "evidence_refs", "q1_harm"]
    _recs = sorted(f for f in os.listdir(recs_dir) if f.endswith(".yaml"))
    _bad_core, _bad_plan = [], []
    for _rf in _recs:
        _rt = open(os.path.join(recs_dir, _rf), encoding="utf-8").read()
        _miss = [k for k in _core if k not in _rt]
        if _miss:
            _bad_core.append(f"{_rf}:{'/'.join(_miss[:2])}")
        if _rf.startswith("rev-") and not any(
                a in _rt for a in ("remediation_plan", "defects_fixed", "revision:", "fixes:")):
            _bad_plan.append(_rf)
    check("G7", f"全部 {len(_recs)} 份判型记录核心六字段齐备（参数化全验）",
          not _bad_core, "; ".join(_bad_core[:3]) if _bad_core else "")
    check("G7", "修订类记录（rev-*）均含修订计划字段（remediation_plan/defects_fixed/revision/fixes 四别名）",
          not _bad_plan, "; ".join(_bad_plan[:3]) if _bad_plan else "")
    _demo = all("breaking" in open(os.path.join(recs_dir, f), encoding="utf-8").read()
                for f in ("rev-spec-sdlc-v1.8.yaml", "rev-spec-meta-v1.1.yaml")
                if os.path.isfile(os.path.join(recs_dir, f)))
    check("G7", "版本语义 breaking 字段示范在场（v1.8/meta-v1.1；此后新记录强制）", _demo)
    print("\n".join(report))

    print(f"\n结果：{'PASS ✅' if errors == 0 else 'FAIL ❌'} | "
          f"{len([r for r in report if '[PASS]' in r])} 项通过 / {errors} 项失败")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
