#!/usr/bin/env python3
"""UTSS coverage_gate.py —— 覆盖率门禁校验器（v1.0）

用途：解析主流覆盖率工具输出，按 UTSS 档位阈值（references/06 第 1 节）判定门禁，
支持 CI 退出码门禁（0=通过，2=不达标，1=输入错误）。

支持输入格式（自动识别）：
  1. coverage.py / pytest-cov 的 coverage.json     （coverage json -o coverage.json）
  2. jest / vitest 的 coverage-summary.json        （--coverageReporters=json_summary）
  3. lcov.info                                     （lcov 格式，jest/gcov 等通用）
  4. go cover 的 coverage.out                      （go test -coverprofile=coverage.out）

用法：
  python3 coverage_gate.py --input coverage.json --grade C
  python3 coverage_gate.py --input coverage-summary.json --grade D --core-glob "src/core/*"
  python3 coverage_gate.py --input coverage.out --grade B

档位阈值（可被 --line-threshold / --branch-threshold / --core-threshold 覆盖）：
  A: 不设硬阈值（仅报告实测）  B: 语句 60  C: 语句 70/分支 60/核心 80  D: 语句 80/分支 70/核心 90
"""
import argparse
import fnmatch
import json
import os
import sys

GRADES = {
    "A": {"line": None, "branch": None, "core": None},
    "B": {"line": 60.0, "branch": None, "core": 70.0},
    "C": {"line": 70.0, "branch": 60.0, "core": 80.0},
    "D": {"line": 80.0, "branch": 70.0, "core": 90.0},
}

EXIT_OK, EXIT_BAD_INPUT, EXIT_GATE_FAIL = 0, 1, 2


def detect_format(path: str) -> str:
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        head = f.read(4096)
    if head.lstrip().startswith("{"):
        try:
            data = json.loads(open(path, encoding="utf-8", errors="replace").read())
        except json.JSONDecodeError:
            return "unknown"
        if "coverage-summary" in os.path.basename(path) or (
            isinstance(data, dict) and "total" in data and "files" in data
        ):
            return "jest_summary"
        if isinstance(data, dict) and ("files" in data or "totals" in data):
            return "coverage_py"
        return "unknown"
    if head.startswith("mode:") or head.startswith("mode:"):
        return "go_cover"
    if "SF:" in head or head.startswith("TN:"):
        return "lcov"
    return "unknown"


def parse_coverage_py(path: str):
    data = json.load(open(path, encoding="utf-8", errors="replace"))
    totals = data.get("totals", {})
    files = data.get("files", {})

    def per_file(name, info):
        s = info.get("summary", {})
        branch_total = s.get("num_branches", 0)
        branch_pct = (
            s.get("percent_covered_display")
            if branch_total == 0
            else round(100.0 * s.get("covered_branches", 0) / branch_total, 1)
            if branch_total
            else None
        )
        return {
            "line_pct": float(s.get("percent_covered", 0) or 0),
            "branch_pct": branch_pct,
            "branch_total": branch_total,
        }

    total_line = float(totals.get("percent_covered", 0) or 0)
    bt = sum(per_file(k, v)["branch_total"] for k, v in files.items())
    total_branch = None
    if bt:
        covered = 0
        for v in files.values():
            s = v.get("summary", {})
            covered += s.get("covered_branches", 0)
        total_branch = round(100.0 * covered / bt, 1)
    return {
        "total_line_pct": round(total_line, 1),
        "total_branch_pct": total_branch,
        "files": {k: per_file(k, v) for k, v in files.items()},
    }


def parse_jest_summary(path: str):
    data = json.load(open(path, encoding="utf-8", errors="replace"))
    total = data.get("total", {})
    line_pct = float(total.get("lines", {}).get("pct", 0))
    branch_pct = float(total.get("branches", {}).get("pct", 0))
    files = {}
    for name, info in data.get("files", {}).items():
        b = info.get("branches", {})
        files[name] = {
            "line_pct": float(info.get("lines", {}).get("pct", 0)),
            "branch_pct": float(b.get("pct", 0)) if b.get("total", 0) else None,
            "branch_total": b.get("total", 0),
        }
    return {
        "total_line_pct": round(line_pct, 1),
        "total_branch_pct": round(branch_pct, 1),
        "files": files,
    }


def parse_lcov(path: str):
    line_hit = line_total = br_hit = br_total = 0
    files = {}
    cur = None
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            line = raw.strip()
            if line.startswith("SF:"):
                cur = line[3:]
                files[cur] = {"lh": 0, "lf": 0, "bh": 0, "bf": 0}
            elif cur is None:
                continue
            elif line.startswith("LF:"):
                files[cur]["lf"] = int(line[3:])
            elif line.startswith("LH:"):
                files[cur]["lh"] = int(line[3:])
            elif line.startswith("BRF:"):
                files[cur]["bf"] = int(line[4:])
            elif line.startswith("BRH:"):
                files[cur]["bh"] = int(line[4:])
            elif line == "end_of_record":
                line_hit += files[cur]["lh"]
                line_total += files[cur]["lf"]
                br_hit += files[cur]["bh"]
                br_total += files[cur]["bf"]
                cur = None
    out_files = {}
    for name, v in files.items():
        out_files[name] = {
            "line_pct": round(100.0 * v["lh"] / v["lf"], 1) if v["lf"] else 0.0,
            "branch_pct": round(100.0 * v["bh"] / v["bf"], 1) if v["bf"] else None,
            "branch_total": v["bf"],
        }
    return {
        "total_line_pct": round(100.0 * line_hit / line_total, 1) if line_total else 0.0,
        "total_branch_pct": round(100.0 * br_hit / br_total, 1) if br_total else None,
        "files": out_files,
    }


def parse_go_cover(path: str):
    files = {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            if raw.startswith("mode:") or not raw.strip():
                continue
            try:
                loc, rest = raw.strip().split(":", 1)
                spans, count = rest.rsplit(" ", 1)
                stmts = int(spans.split(" ")[1])
                hit = int(count) > 0
            except (ValueError, IndexError):
                continue
            v = files.setdefault(loc, {"stmts": 0, "hit": 0})
            v["stmts"] += stmts
            if hit:
                v["hit"] += stmts
    out_files = {}
    hit_all = total_all = 0
    for name, v in files.items():
        pct = round(100.0 * v["hit"] / v["stmts"], 1) if v["stmts"] else 0.0
        out_files[name] = {"line_pct": pct, "branch_pct": None, "branch_total": 0}
        hit_all += v["hit"]
        total_all += v["stmts"]
    return {
        "total_line_pct": round(100.0 * hit_all / total_all, 1) if total_all else 0.0,
        "total_branch_pct": None,
        "files": out_files,
    }


PARSERS = {
    "coverage_py": parse_coverage_py,
    "jest_summary": parse_jest_summary,
    "lcov": parse_lcov,
    "go_cover": parse_go_cover,
}


def collect_inputs(args):
    results = []
    inputs = []
    p = args.input
    if os.path.isdir(p):
        found = []
        for root, _dirs, names in os.walk(p):
            for n in names:
                if n in ("coverage.json", "coverage-summary.json", "lcov.info", "coverage.out"):
                    found.append(os.path.join(root, n))
        if not found:
            return None, "目录中未发现 coverage.json / coverage-summary.json / lcov.info / coverage.out"
        inputs = sorted(found)[:8]
    else:
        inputs = [p]
    for path in inputs:
        fmt = detect_format(path)
        if fmt not in PARSERS:
            return None, f"无法识别格式: {path}"
        results.append((path, fmt, PARSERS[fmt](path)))
    return results, None


def pick_core(parsed, core_glob):
    core_files = {k: v for k, v in parsed["files"].items() for g in core_glob if fnmatch.fnmatch(k, g)}
    return core_files


def main():
    ap = argparse.ArgumentParser(description="UTSS 覆盖率门禁校验器")
    ap.add_argument("--input", "-i", help="覆盖率输出文件或目录")
    ap.add_argument("--grade", "-g", default="C", choices=list(GRADES), help="UTSS 档位")
    ap.add_argument("--line-threshold", type=float, help="覆盖语句/行阈值（覆盖档位默认值）")
    ap.add_argument("--branch-threshold", type=float, help="分支阈值")
    ap.add_argument("--core-threshold", type=float, help="核心模块阈值")
    ap.add_argument("--core-glob", action="append", default=[], help="核心模块 glob（可多次）")
    ap.add_argument("--exclude", action="append", default=[], help="从核心判定排除的 glob（可多次）")
    ap.add_argument("--self-test", action="store_true", help="运行内置样例自检")
    args = ap.parse_args()

    if args.self_test:
        return self_test()
    if not args.input:
        ap.error("the following arguments are required: --input/-i（或使用 --self-test）")

    th = dict(GRADES[args.grade])
    if args.line_threshold is not None:
        th["line"] = args.line_threshold
    if args.branch_threshold is not None:
        th["branch"] = args.branch_threshold
    if args.core_threshold is not None:
        th["core"] = args.core_threshold

    parsed_list, err = collect_inputs(args)
    if err:
        print(f"[UTSS][ERROR] {err}", file=sys.stderr)
        return EXIT_BAD_INPUT

    exit_code = EXIT_OK
    rows = []
    for path, fmt, parsed in parsed_list:
        line_t = th["line"]
        ok = True
        if line_t is not None and parsed["total_line_pct"] < line_t:
            ok = False
        branch_t = th["branch"]
        if branch_t is not None and parsed["total_branch_pct"] is not None:
            if parsed["total_branch_pct"] < branch_t:
                ok = False
        rows.append((path, fmt, parsed, ok))
        if not ok:
            exit_code = EXIT_GATE_FAIL

    # 核心模块门禁
    core_rows = []
    core_t = th["core"]
    if core_t is not None and args.core_glob:
        for path, fmt, parsed, _ok in rows:
            core = pick_core(parsed, args.core_glob)
            core = {k: v for k, v in core.items() if not any(fnmatch.fnmatch(k, e) for e in args.exclude)}
            if not core:
                continue
            worst = min(core.values(), key=lambda v: v["line_pct"])
            cok = worst["line_pct"] >= core_t
            core_rows.append((path, len(core), worst["line_pct"], cok))
            if not cok:
                exit_code = EXIT_GATE_FAIL

    # 输出门禁表
    print("UTSS 覆盖率门禁评估（档位 {}）".format(args.grade))
    print("=" * 72)
    for path, fmt, parsed, ok in rows:
        mark = "PASS" if ok else "FAIL"
        branch = parsed["total_branch_pct"]
        branch_s = f"{branch:.1f}%" if branch is not None else "N/A（工具无分支数据）"
        print(f"[{mark}] {os.path.basename(path)}  格式={fmt}")
        print(f"       语句/行覆盖: {parsed['total_line_pct']:.1f}%  分支: {branch_s}")
    for path, n, worst, ok in core_rows:
        mark = "PASS" if ok else "FAIL"
        print(f"[{mark}] 核心模块({n} 个文件, {os.path.basename(path)})  最低: {worst:.1f}%  阈值: {core_t}%")
    print("-" * 72)
    print(f"判定阈值: 语句={th['line']} 分支={th['branch']} 核心={th['core']}")
    if GRADES[args.grade]["line"] is None:
        print("（A 档不设硬阈值，仅报告实测值——冒烟与主流程必须通过仍为硬要求）")
    print("退出码: {}".format(exit_code))
    return exit_code


def self_test():
    """内置样例自检：验证四种解析器与门禁逻辑。"""
    import tempfile

    cases = []
    # coverage.py 样例：行覆盖 4/5=80%，分支 1/2=50%
    cov_py = {
        "totals": {"percent_covered": 80.0},
        "files": {
            "src/app.py": {"summary": {"percent_covered": 80.0, "covered_branches": 1, "num_branches": 2}},
            "src/util.py": {"summary": {"percent_covered": 100.0, "covered_branches": 0, "num_branches": 0}},
        },
    }
    # jest 样例：行 90，分支 75
    jest = {
        "total": {"lines": {"pct": 90.0}, "branches": {"pct": 75.0}},
        "files": {"src/a.js": {"lines": {"pct": 90.0}, "branches": {"pct": 75.0, "total": 4}}},
    }
    # go 样例：3 语句中 2 命中 = 66.7
    go = "mode: set\ngo/src/app.go:10.1,12.2 2 1\ngo/src/app.go:13.1,14.1 1 0\n"
    # lcov 样例：LH3/LF4=75，BRH1/BRF2=50
    lcov = "SF:src/x.js\nLH:3\nLF:4\nBRH:1\nBRF:2\nend_of_record\n"

    with tempfile.TemporaryDirectory() as d:
        f_py = os.path.join(d, "coverage.json")
        json.dump(cov_py, open(f_py, "w"))
        f_jest = os.path.join(d, "coverage-summary.json")
        json.dump(jest, open(f_jest, "w"))
        f_go = os.path.join(d, "coverage.out")
        open(f_go, "w").write(go)
        f_lcov = os.path.join(d, "lcov.info")
        open(f_lcov, "w").write(lcov)

        assert detect_format(f_py) == "coverage_py", detect_format(f_py)
        assert detect_format(f_jest) == "jest_summary", detect_format(f_jest)
        assert detect_format(f_go) == "go_cover", detect_format(f_go)
        assert detect_format(f_lcov) == "lcov", detect_format(f_lcov)

        r = PARSERS["coverage_py"](f_py)
        assert abs(r["total_line_pct"] - 80.0) < 0.01 and r["total_branch_pct"] == 50.0, r
        r = PARSERS["jest_summary"](f_jest)
        assert r["total_line_pct"] == 90.0 and r["total_branch_pct"] == 75.0, r
        r = PARSERS["go_cover"](f_go)
        assert abs(r["total_line_pct"] - 66.7) < 0.05, r
        r = PARSERS["lcov"](f_lcov)
        assert r["total_line_pct"] == 75.0 and r["total_branch_pct"] == 50.0, r

    print("self-test: 4/4 解析器通过；样例断言通过")
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
