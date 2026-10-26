#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""sarif_dedup.py — V1.9.22 引擎批（协作链缺口#3 收口：CR×UCSS SARIF 去重键机读闭环）
协议正本：CR 与 UCSS 双门并行去重互证——键 = cwe+file+line±3（邻近行判重）。
本工具把 M0-M3 互证协议机读化：validate（键三要素完整性）/ verify（两组 finding ±3 邻近互证）。
诚实边界：本工具管 SARIF×SARIF（UCSS 重扫/多引擎对比同适用）；CR 问题集导出 SARIF 的
转换约定就绪前，CR×UCSS 全互证为即插即用（欠账显式标注）。

用法：
  sarif_dedup.py --validate x.sarif
  sarif_dedup.py --verify a.sarif b.sarif
  sarif_dedup.py --selftest
"""
import json, os, sys, tempfile

def load_findings(path):
    """SARIF → [(cwe, file, line)]。cwe 取 ruleId（或 properties.cwe）；line 取 startLine。"""
    data = json.load(open(path, encoding="utf-8"))
    out, bad = [], []
    for run in data.get("runs", []):
        for r in run.get("results", []):
            cwe = r.get("ruleId") or r.get("properties", {}).get("cwe")
            loc = r.get("locations", [{}])[0].get("physicalLocation", {}) if r.get("locations") else {}
            f = loc.get("artifactLocation", {}).get("uri")
            ln = loc.get("region", {}).get("startLine")
            if cwe and f and isinstance(ln, int):
                out.append((str(cwe), f, ln))
            else:
                bad.append({"ruleId": cwe, "file": f, "line": ln})
    return out, bad


def dedup_key(f):
    """规范去重键：cwe|file（归一正斜杠）|line。"""
    cwe, f, ln = f
    return f"{cwe}|{f.replace(os.sep, '/')}|{ln}"


def pair_verify(fa, fb):
    """±3 邻近互证：返回 (重复对, 仅A, 仅B)。同 cwe+file 且 |lineA-lineB|<=3 判重。"""
    dup, only_a, only_b = [], list(fa), list(fb)
    for a in fa:
        for b in list(only_b):
            if a[0] == b[0] and a[1].replace(os.sep, "/") == b[1].replace(os.sep, "/") and abs(a[2] - b[2]) <= 3:
                dup.append((dedup_key(a), dedup_key(b)))
                only_a.remove(a)
                only_b.remove(b)
                break
    return dup, only_a, only_b


def main():
    a = sys.argv[1:]
    if "--selftest" in a:
        return selftest()
    if "--validate" in a:
        p = a[a.index("--validate") + 1]
        findings, bad = load_findings(p)
        for x in bad:
            print(f"  ✗ 键三要素缺失：ruleId={x['ruleId']} file={x['file']} line={x['line']}（cwe+file+line 不可缺）")
        keys = [dedup_key(f) for f in findings]
        dups = len(keys) - len(set(keys))
        if dups:
            print(f"  ✗ 组内精确重键 {dups} 条（同 cwe+file+line）")
        print(f"[SARIF-VALIDATE] {p}：{len(findings)} 条完整 / {len(bad)} 条缺要素 / {dups} 组内重键"
              + ("——FAIL" if (bad or dups) else "——PASS"))
        return 1 if (bad or dups) else 0
    if "--verify" in a:
        pa, pb = a[a.index("--verify") + 1], a[a.index("--verify") + 2]
        fa, ba = load_findings(pa)
        fb, bb = load_findings(pb)
        dup, oa, ob = pair_verify(fa, fb)
        print(f"[SARIF-VERIFY] A={pa}（{len(fa)}）× B={pb}（{len(fb)}）——键 cwe+file+line±3")
        print(f"  互证重复 {len(dup)} 对（双方共见，按 M0-M3 不重复处理）")
        for ka, kb in dup[:5]:
            print(f"    ≡ {ka} ⟷ {kb}")
        print(f"  仅A {len(oa)}（B 引擎未见——A 独有发现需走人工/CR 裁决）")
        print(f"  仅B {len(ob)}（A 引擎未见——B 独有发现需走人工/CR 裁决）")
        for f in oa[:3]:
            print(f"    A: {dedup_key(f)}")
        for f in ob[:3]:
            print(f"    B: {dedup_key(f)}")
        print(f"SARIF 互证：重复 {len(dup)} + 仅A {len(oa)} + 仅B {len(ob)} = 双门联合 {len(dup) + len(oa) + len(ob)}"
              + ("——FAIL（载入缺要素）" if (ba or bb) else "——PASS"))
        return 1 if (ba or bb) else 0
    print("用法：--validate x.sarif｜--verify a.sarif b.sarif｜--selftest")
    return 2


def _mk_sarif(findings):
    return {"runs": [{"results": [
        {"ruleId": c, "locations": [{"physicalLocation": {
            "artifactLocation": {"uri": f}, "region": {"startLine": n}}}]}
        for c, f, n in findings]}]}


def selftest():
    fails = []
    with tempfile.TemporaryDirectory() as d:
        pa, pb = os.path.join(d, "a.sarif"), os.path.join(d, "b.sarif")
        A = [("CWE79", "src/app.py", 10), ("CWE79", "src/app.py", 13),   # ±3 邻近→判重
             ("CWE79", "src/app.py", 40),                                 # 同 cwe+file 远行→不判重
             ("CWE200", "src/ui.js", 7)]                                  # A 独有
        B = [("CWE79", "src/app.py", 11),                                 # 与 A[0] 互证
             ("CWE79", "src/app.py", 50),                                 # B 独有
             ("CWE200", "src/ui.js", 8)]                                  # 与 A[3] ±1 判重
        json.dump(_mk_sarif(A), open(pa, "w"))
        json.dump(_mk_sarif(B), open(pb, "w"))
        dup, oa, ob = pair_verify(A, B)
        # 协议语义：一条 B 只互证一条 A（首配贪心消耗）——A[1](79,13) 与 B(79,11) 虽邻近，
        # 但 B(11) 已消耗于 A(10) → A[1] 落仅A（独有发现走人工/CR 裁决，不作双份证据）
        if len(dup) != 2:
            fails.append(f"互证对期望 2（±3/±1 邻近首配消耗），实得 {len(dup)}")
        if len(oa) != 2 or {x[2] for x in oa} != {13, 40}:
            fails.append(f"仅A 期望 2 条（line13 邻近耗尽+line40 远行），实得 {[(x[0], x[2]) for x in oa]}")
        if len(ob) != 1 or ob[0][2] != 50:
            fails.append(f"仅B 期望 1 条（line50），实得 {[(x[0], x[2]) for x in ob]}")
        pc = os.path.join(d, "c.sarif")
        json.dump(_mk_sarif([("CWE79", "x.py", 1), ("CWE79", "x.py", 1)]), open(pc, "w"))  # 组内精确重键
        _, bad = load_findings(pc)
        if bad:
            fails.append("载入完整条目被误判缺要素")
        json.dump({"runs": [{"results": [{"ruleId": None, "locations": []}]}]}, open(pc, "w"))
        _, bad = load_findings(pc)
        if not bad:
            fails.append("缺三要素条目未报警")
    for x in fails:
        print(f"  ✗[sarif] {x}")
    print(f"selftest：SARIF 去重键 {'FAIL' if fails else 'PASS（4 场景：±3 判重/远行不判重/独有分类/组内重键与缺要素报警）'}")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
