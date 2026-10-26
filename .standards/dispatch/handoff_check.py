#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""handoff_check.py — V1.9.21 交接物门禁（协作链批缺口 #2 收口）
正本：INTEROP-MATRIX.md §五 交接物注册表（派生零漂移·双布局探测同 trigger_check）。
语义：链上交接断了体系报警——探测缺失 → FAIL 列缺失清单（报警不阻断业务；
阻断与否由链上顺序门裁决）。selftest 构造 sandbox 验证 PASS/FAIL 正确翻转。

用法：
  handoff_check.py --list                          # 列注册表
  handoff_check.py --chain A --stage post-fix --project <根>   # 链×阶段门禁
  handoff_check.py --selftest
"""
import glob, os, re, sys, tempfile

ROOT = os.path.dirname(os.path.abspath(__file__))


def _matrix_path():
    for p in (os.path.join(ROOT, "..", "..", "INTEROP-MATRIX.md"),                # 安装平铺：dispatch→.standards→项目根
              os.path.join(ROOT, "..", "..", "..", "02_统一调度入口", "INTEROP-MATRIX.md"),  # 源树 dispatch 深层
              os.path.join(ROOT, "INTEROP-MATRIX.md")):                            # 兜底
        if os.path.isfile(p):
            return p
    return None


def load_handoffs():
    """解析 §五 注册表行：| 名称 | 键 | 探测模式 | 链 | 阶段 | 强度 |"""
    p = _matrix_path()
    if not p:
        return []
    out = []
    in_sec = False
    for line in open(p, encoding="utf-8"):
        if line.startswith("## 五、"):
            in_sec = True
            continue
        if in_sec and line.startswith("## "):
            break
        m = re.match(r"^\|([^|]+)\|([^|]+)\|([^|]+)\|([^|]+)\|([^|]+)\|([^|]+)\|\s*$", line)
        if not m or "---" in line or "交接物" in line.split("|")[1]:
            continue
        name, key, pat, chains, stage, strength = (x.strip() for x in m.groups())
        if key == "键":
            continue
        out.append(dict(name=name, key=key, pat=pat, chains=chains, stage=stage, strength=strength))
    return out


def probe(project, pat):
    """glob 探测。特殊语义：`*.md≥N`（数量门槛，N=3 三单/N=1 评审报告）。"""
    if "≥" in pat:
        base, n = pat.split("≥")[0], int(pat.split("≥")[1])
        hits = glob.glob(os.path.join(project, base))
        return len(hits) >= n, [os.path.relpath(h, project) for h in hits]
    hits = glob.glob(os.path.join(project, pat))
    return bool(hits), [os.path.relpath(h, project) for h in hits]


def check(project, chain=None, stage=None):
    """返回 (缺失清单, 已命中清单)。chain 如 'A'（链A）；stage 如 post-fix。"""
    missing, found = [], []
    for h in load_handoffs():
        if chain and f"链{chain}" not in h["chains"] and "全" != h["chains"]:
            continue
        if stage and stage != h["stage"]:
            continue
        ok, hits = probe(project, h["pat"])
        (found if ok else missing).append((h, hits))
    return missing, found


def main():
    a = sys.argv[1:]
    if "--selftest" in a:
        return selftest()
    if "--list" in a:
        hs = load_handoffs()
        print(f"交接物注册表：{len(hs)} 项（INTEROP-MATRIX §五 派生）")
        for h in hs:
            print(f"  {h['key']:14s} {h['name']:10s} 链{h['chains']:6s} {h['stage']:10s} [{h['strength']}] pat={h['pat']}")
        return 0 if len(hs) == 7 else 1
    chain = stage = project = None
    if "--chain" in a:
        chain = a[a.index("--chain") + 1].lstrip("链")
    if "--stage" in a:
        stage = a[a.index("--stage") + 1]
    project = a[a.index("--project") + 1] if "--project" in a else os.getcwd()
    if not chain:
        print("用法：--chain A|B|C|D [--stage post-fix] [--project 根]｜--list｜--selftest")
        return 2
    missing, found = check(project, chain, stage)
    print(f"[HANDOFF-CHECK] 链{chain}{'·' + stage if stage else ''} @ {project}")
    for h, hits in found:
        print(f"  ✓ {h['name']}（{h['key']}）×{len(hits)}：{', '.join(hits[:3])}{'…' if len(hits) > 3 else ''}")
    for h, _ in missing:
        print(f"  ✗ {h['name']}（{h['key']}）缺失——探测 {h['pat']}（{h['strength']}门禁：链上交接断裂报警）")
    print(f"交接物门禁：{len(found)} 在位 / {len(missing)} 缺失" + ("——FAIL 报警" if missing else "——PASS"))
    return 1 if missing else 0


def selftest():
    fails = []
    hs = load_handoffs()
    if len(hs) != 7:
        fails.append(f"注册表解析 {len(hs)} != 7（§五 格式漂移或正本缺失——含 themis-rpt·V1.9.26）")
    with tempfile.TemporaryDirectory() as d:
        # 空项目：链A 全阶段应 6 缺？——chain A stage 过滤下应缺失
        m, f = check(d, "A")
        if not m or f:
            fails.append(f"空项目链A：期望全缺失报警，实得 {len(f)} 在位 {len(m)} 缺失")
        # 构造链A post-fix 全交接物：三单×3 + 复验结论 + sarif
        os.makedirs(os.path.join(d, ".standards/aegis/slips"), exist_ok=True)
        os.makedirs(os.path.join(d, ".standards/audit"), exist_ok=True)
        os.makedirs(os.path.join(d, ".standards/scan"), exist_ok=True)
        for i in ("fix", "verify", "close"):
            open(os.path.join(d, f".standards/aegis/slips/{i}-001.md"), "w").write("# 单")
        open(os.path.join(d, ".standards/audit/cr-recheck-001.md"), "w").write("# 复验")
        open(os.path.join(d, ".standards/scan/scan-001.sarif"), "w").write("{}")
        m2, f2 = check(d, "A", "post-fix")
        if m2 or not f2:
            fails.append(f"齐备项目链A post-fix：期望 0 缺失，实得缺失 {[h['key'] for h, _ in m2]}")
        # 探测负例：三单只 2 张 → aegis-3slips FAIL 翻转
        os.remove(os.path.join(d, ".standards/aegis/slips/close-001.md"))
        m3, _ = check(d, "A", "post-fix")
        if not any(h["key"] == "aegis-3slips" for h, _ in m3):
            fails.append("三单仅 2 张：aegis-3slips 未报警（≥3 语义失效）")
        # 链C pre-lr：rebuild-plan.md 存在性门
        m4, _ = check(d, "C", "pre-lr")
        if not any(h["key"] == "lpu-plan" for h, _ in m4):
            fails.append("链C pre-lr 无 rebuild-plan.md：lpu-plan 未报警")
        open(os.path.join(d, "rebuild-plan.md"), "w").write("# plan")
        m5, _ = check(d, "C", "pre-lr")
        if any(h["key"] == "lpu-plan" for h, _ in m5):
            fails.append("rebuild-plan.md 在位仍报警（探测失效）")
        # 场景6（V1.9.26）：themis-rpt——全链 post-doc 阶段门翻转
        m6, _ = check(d, "A", "post-doc")
        if not any(h["key"] == "themis-rpt" for h, _ in m6):
            fails.append("链A post-doc 无评审报告：themis-rpt 未报警")
        os.makedirs(os.path.join(d, ".standards/themis/records"), exist_ok=True)
        open(os.path.join(d, ".standards/themis/records/RPT-001-demo.md"), "w").write("# RPT")
        m7, _ = check(d, "A", "post-doc")
        if any(h["key"] == "themis-rpt" for h, _ in m7):
            fails.append("RPT-001.md 在位仍报警（≥1 探测失效）")
    for x in fails:
        print(f"  ✗[handoff] {x}")
    print(f"selftest：交接物门禁 {'FAIL' if fails else 'PASS（6 场景：空项目全报警/齐备 0 缺/三单缺 1 报警/链C 硬门翻转/themis-rpt post-doc 翻转）'}")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
