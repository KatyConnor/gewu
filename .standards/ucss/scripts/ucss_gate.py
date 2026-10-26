#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
UCSS v1.0 门禁脚本：聚合 SARIF 结果并按规范等级判定门禁。
用法：
  python3 ucss_gate.py --fail-on critical,high [--min-severity MEDIUM] *.sarif
  python3 ucss_gate.py --summary-only semgrep.sarif trivy.sarif
退出码：0=PASS，2=FAIL（发现阻断项），1=参数/文件错误
"""
import argparse, glob, json, sys

SEV_ORDER = {"none": 0, "info": 1, "note": 1, "warning": 2, "error": 3}
SEV_LABEL = {"error": "Critical/High", "warning": "Medium", "note": "Low/Info", "info": "Info", "none": "Info"}

def load_findings(paths):
    findings = []
    for pattern in paths:
        for p in sorted(glob.glob(pattern)):
            try:
                data = json.load(open(p, encoding="utf-8"))
            except Exception as e:
                print(f"[warn] 无法解析 {p}: {e}", file=sys.stderr)
                continue
            for run in data.get("runs", []):
                tool = run.get("tool", {}).get("driver", {}).get("name", "unknown")
                rules = {r.get("id"): r for r in run.get("tool", {}).get("driver", {}).get("rules", [])}
                for res in run.get("results", []):
                    rule_id = res.get("ruleId", "?")
                    level = res.get("level", "warning")
                    meta = rules.get(rule_id, {}).get("properties", {})
                    props = res.get("properties", {})
                    cwe = props.get("cwe") or meta.get("cwe") or ""
                    loc = ""
                    locs = res.get("locations", [])
                    if locs:
                        pa = locs[0].get("physicalLocation", {})
                        loc = f"{pa.get('artifactLocation', {}).get('uri', '?')}:{pa.get('region', {}).get('startLine', '?')}"
                    findings.append({
                        "tool": tool, "rule": rule_id, "level": level,
                        "cwe": cwe, "location": loc,
                        "message": (res.get("message", {}) or {}).get("text", "")[:120],
                        "file": p,
                    })
    return findings

def main():
    ap = argparse.ArgumentParser(description="UCSS gate")
    ap.add_argument("sarif", nargs="+", help="SARIF 文件或通配符")
    ap.add_argument("--fail-on", default="critical,high", help="阻断等级：critical,high[,medium]")
    ap.add_argument("--min-severity", default=None, help="仅统计不低于该等级：critical|high|medium|low")
    ap.add_argument("--summary-only", action="store_true")
    args = ap.parse_args()

    findings = load_findings(args.sarif)
    # level: error→Critical/High, warning→Medium, note→Low/Info
    blocking_levels = {"error"} | ({"warning"} if "medium" in args.fail_on else set())
    if args.min_severity:
        floor = SEV_ORDER.get({"critical": "error", "high": "error", "medium": "warning", "low": "note"}.get(args.min_severity, "note"))
        findings = [f for f in findings if SEV_ORDER.get(f["level"], 0) >= floor]

    counts = {"error": 0, "warning": 0, "note": 0}
    for f in findings:
        counts[f["level"]] = counts.get(f["level"], 0) + 1

    print("=" * 56)
    print("UCSS v1.0 扫描摘要")
    print(f"  Critical/High (error)   : {counts.get('error', 0)}")
    print(f"  Medium        (warning) : {counts.get('warning', 0)}")
    print(f"  Low/Info      (note)    : {counts.get('note', 0)}")
    print("=" * 56)
    for f in findings:
        if f["level"] in blocking_levels or not args.summary_only:
            cwe = f" {f['cwe']}" if f["cwe"] else ""
            print(f"[{SEV_LABEL.get(f['level'], f['level'])}] {f['rule']}{cwe} @ {f['location']}  ({f['tool']})")
            if f["message"]:
                print(f"    {f['message']}")

    blocking = [f for f in findings if f["level"] in blocking_levels]
    if args.summary_only:
        print("（summary-only 模式，不做门禁判定）")
        return 0
    if blocking:
        print(f"❌ 门禁 FAIL：{len(blocking)} 项达到阻断等级（{args.fail_on}）")
        return 2
    print("✅ 门禁 PASS")
    return 0

if __name__ == "__main__":
    sys.exit(main())
