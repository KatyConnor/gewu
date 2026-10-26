#!/usr/bin/env python3
"""UTSS init_test_docs.py —— 项目测试文档骨架初始化（v1.0）

用途：在目标项目生成 tests/qa/ 四件套（test-plan / test-cases / defect-report /
test-report），从 UTSS 模板派生并预填项目信息；已存在的文件跳过不覆盖。

用法：
  python3 init_test_docs.py --project-root /path/to/project --grade C --project "DBG 平台"
  python3 init_test_docs.py --project-root . --grade B --project "demo" --list
"""
import argparse
import os
import sys

TEMPLATES = ["test-plan.md", "test-cases.md", "test-execution.md", "defect-report.md", "test-report.md"]
GRADE_HINT = {
    "A": "原型/演示档：冒烟 + 核心路径必做，覆盖率仅报告实测",
    "B": "小型档：语句覆盖 ≥60%，核心 ≥70%",
    "C": "标准档：语句 ≥70% 分支 ≥60% 核心 ≥80%，S1-S3 全跑",
    "D": "关键档：语句 ≥80% 分支 ≥70% 核心 ≥90%，安全测试强制（联动 UCSS）",
}


def main():
    ap = argparse.ArgumentParser(description="UTSS 项目测试文档骨架初始化")
    ap.add_argument("--project-root", default=".", help="项目根目录（默认当前目录）")
    ap.add_argument("--grade", "-g", default="C", choices=list(GRADE_HINT), help="UTSS 档位")
    ap.add_argument("--project", "-p", default="", help="项目名（写入文档标题）")
    ap.add_argument("--outdir", default="tests/qa", help="输出目录（默认 tests/qa）")
    ap.add_argument("--list", action="store_true", help="仅列出将生成的文件，不写入")
    ap.add_argument("--self-test", action="store_true", help="在临时目录自检")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    tpl_dir = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "templates"))
    if not os.path.isdir(tpl_dir):
        print(f"[UTSS][ERROR] 未找到模板目录: {tpl_dir}", file=sys.stderr)
        return 1

    out_root = os.path.join(args.project_root, args.outdir)
    created, skipped = [], []
    for name in TEMPLATES:
        dst = os.path.join(out_root, name)
        if os.path.exists(dst):
            skipped.append(dst)
            continue
        if args.list:
            created.append(dst)
            continue
        tpl_path = os.path.join(tpl_dir, name)
        with open(tpl_path, encoding="utf-8") as f:
            content = f.read()
        title = args.project or os.path.basename(os.path.abspath(args.project_root))
        content = content.replace("{项目/迭代名}", f"{title}")
        header = (
            f"<!-- 由 UTSS init_test_docs.py 生成 | 档位 {args.grade} | {GRADE_HINT[args.grade]} -->\n\n"
        )
        os.makedirs(out_root, exist_ok=True)
        with open(dst, "w", encoding="utf-8") as f:
            f.write(header + content)
        created.append(dst)

    print("UTSS 测试文档骨架初始化")
    print("=" * 60)
    print(f"项目: {args.project or args.project_root}  档位: {args.grade}（{GRADE_HINT[args.grade]}）")
    for p in created:
        print(f"  生成: {p}")
    for p in skipped:
        print(f"  跳过(已存在): {p}")
    if args.list:
        print("（--list 模式：未写入任何文件）")
    print("\n下一步：填写 test-plan.md 的测试项与风险表 → 通过 G0 后开始 T0 冒烟。")
    return 0


def self_test():
    import tempfile

    with tempfile.TemporaryDirectory() as d:
        rc = main_inner_safe(d, grade="C", project="selftest")
        assert rc == 0, f"exit={rc}"
        for name in TEMPLATES:
            p = os.path.join(d, "tests", "qa", name)
            assert os.path.exists(p), f"missing {p}"
        # 幂等：再跑一遍应全部跳过
        rc2 = main_inner_safe(d, grade="C", project="selftest")
        assert rc2 == 0
    print("self-test: 五件套生成 + 幂等跳过 通过")
    return 0


def main_inner_safe(root, grade, project):
    import contextlib
    import io

    sys.argv = [
        "init_test_docs.py",
        "--project-root", root,
        "--grade", grade,
        "--project", project,
    ]
    buf = io.StringIO()
    try:
        with contextlib.redirect_stdout(buf):
            return main()
    except SystemExit as e:
        return int(e.code or 0)


if __name__ == "__main__":
    sys.exit(main())
