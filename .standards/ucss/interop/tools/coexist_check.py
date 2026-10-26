#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
coexist_check.py — UCSS/UTSS 装前共存检测（集成适配设计 v1.0 随卷工具）
用途：在把 code-scan / utss 技能包安装进目标 skills 目录之前，检测
      ① 目录名冲突（F1）② SKILL.md name 字段冲突（F1'）③ Python 版本基线
      ④ 宿主 CI 门禁侦测（提示 M0 起步）⑤ 网络模式状态
纪律：纯检测、零写入、零网络——「不覆盖原则」的执行体（设计 §3/§4.1）。
用法：
  python3 coexist_check.py --skills-dir ~/.zcode/skills
  python3 coexist_check.py --skills-dir <dir> --project-root <repo> --json report.json
退出码：0 = 可安全安装；1 = 发现冲突（按报告消解后重检）；2 = 环境问题
"""
import argparse
import glob as globlib
import json
import os
import re
import sys

UCSS_DIR_NAME = "code-scan"
UCSS_SKILL_NAME = "code-scan"          # UCSS SKILL.md frontmatter name
UTSS_DIR_NAME = "utss"
UTSS_SKILL_NAME = "utss-test-standards"  # UTSS SKILL.md frontmatter name

# 宿主 CI 门禁关键词（best-effort 侦测，用于建议 M0 起步，不判定对错）
HOST_GATE_KEYWORDS = ["semgrep", "codeql", "bandit", "gosec", "gitleaks",
                      "trivy", "fortify", "checkmarx", "sonarqube", "sonar-scanner"]
HOST_TEST_KEYWORDS = ["pytest", "junit", "coverage", "jacoco", "lcov", "go test", "go tool cover"]


def read_frontmatter_name(skill_md_path):
    """解析 SKILL.md YAML frontmatter 的 name 字段（容错：无 frontmatter 返回 None）。"""
    try:
        with open(skill_md_path, encoding="utf-8") as f:
            text = f.read(4096)
    except OSError:
        return None
    m = re.match(r"\s*---\s*\n(.*?)\n---", text, re.DOTALL)
    if not m:
        return None
    for line in m.group(1).splitlines():
        mm = re.match(r"^name:\s*['\"]?([A-Za-z0-9_\-]+)", line)
        if mm:
            return mm.group(1)
    return None


def check_dir_conflicts(skills_dir):
    """C1 目录名冲突：code-scan / utss 已存在 → 覆盖风险，必须消解。"""
    issues = []
    for dname, label in ((UCSS_DIR_NAME, "UCSS(code-scan)"), (UTSS_DIR_NAME, "UTSS(utss)")):
        if os.path.isdir(os.path.join(skills_dir, dname)):
            issues.append({
                "code": "F1-DIR",
                "level": "conflict",
                "detail": f"目标目录已存在 {dname}/ —— 直接 cp -r 将静默覆盖既有技能（可能是宿主标准）",
                "options": ["O1 改名安装（目录与 SKILL.md name 字段同步改，如 code-scan-ucss）",
                            "O2 分层安装（宿主技能留全局，本包装项目级 <项目>/.zcode/skills/）",
                            "O3 人工合并评审（两技能 diff 对照后并轨为单一技能）"],
            })
    return issues


def check_name_conflicts(skills_dir):
    """C2 name 字段冲突：目录名不同但 frontmatter name 相同 → 触发路由竞争。"""
    issues = []
    for skill_md in globlib.glob(os.path.join(skills_dir, "*", "SKILL.md")):
        dname = os.path.basename(os.path.dirname(skill_md))
        if dname in (UCSS_DIR_NAME, UTSS_DIR_NAME):
            continue  # 已由 C1 报告（或为待升级的旧版本本包）
        name = read_frontmatter_name(skill_md)
        for target_name, label in ((UCSS_SKILL_NAME, "UCSS"), (UTSS_SKILL_NAME, "UTSS")):
            if name == target_name:
                issues.append({
                    "code": "F1-NAME",
                    "level": "conflict",
                    "detail": f"{dname}/SKILL.md 声明 name: {name}（与{label}技能名相同）——"
                              f"目录名虽不同，但智能体触发路由会竞争",
                    "options": ["O1 本包改名安装（name 字段同步改）",
                                "O2 分层安装（项目级隔离）",
                                "O3 人工合并评审"],
                })
    return issues


def check_python():
    """C3 Python 版本基线：脚本为纯标准库，要求 >= 3.8。"""
    v = sys.version_info
    if v < (3, 8):
        return [{
            "code": "ENV-PY",
            "level": "environment",
            "detail": f"Python {v.major}.{v.minor} < 3.8，四个门禁脚本无法运行",
            "options": ["升级解释器或在 CI 指定 python3.8+"],
        }]
    return []


def check_host_ci(project_root):
    """C4 宿主 CI 门禁侦测（best-effort）：发现既有安全/测试门禁 → 建议 M0 起步。"""
    notes = []
    candidates = []
    for pat in (".gitlab-ci.yml", "Jenkinsfile",
                os.path.join(".github", "workflows", "*.yml"),
                os.path.join(".github", "workflows", "*.yaml")):
        candidates.extend(globlib.glob(os.path.join(project_root, pat)))
    seen = set()
    for cf in candidates:
        try:
            with open(cf, encoding="utf-8", errors="replace") as f:
                text = f.read().lower()
        except OSError:
            continue
        hits = sorted({kw for kw in HOST_GATE_KEYWORDS + HOST_TEST_KEYWORDS if kw in text})
        for kw in hits:
            if kw not in seen:
                seen.add(kw)
    if seen:
        sec = sorted(set(seen) & {k for k in HOST_GATE_KEYWORDS})
        notes.append({
            "code": "HOST-GATE",
            "level": "info",
            "detail": f"侦测到宿主既有门禁/测试关键词：{', '.join(sorted(seen))}"
                      + (f"（安全扫描域：{', '.join(sec)}）" if sec else ""),
            "options": ["按设计 §6.1/§7：宿主已管辖的域（SAST/测试）UCSS 从 M0 观察模式起步"
                        "（ucss_gate.py --summary-only，exit 0 不影响流水线），空缺域可直接 M2"],
        })
    return notes


def check_network_mode():
    """C5 网络模式状态：UCSS 默认 OFFLINE，此处仅提示当前显式设置。"""
    mode = os.environ.get("UCSS_NETWORK_MODE")
    if mode and mode.upper() in ("ONLINE", "SYNC"):
        return [{
            "code": "NET-MODE",
            "level": "info",
            "detail": f"UCSS_NETWORK_MODE={mode}（非默认 OFFLINE）。共存环境建议保持 OFFLINE，"
                      "避免与宿主网络的出网策略互相干扰",
            "options": ["unset UCSS_NETWORK_MODE 恢复安全默认"],
        }]
    return []


def main():
    ap = argparse.ArgumentParser(description="UCSS/UTSS 装前共存检测（纯检测零写入）")
    ap.add_argument("--skills-dir", required=True, help="目标技能目录（如 ~/.zcode/skills 或项目级 .zcode/skills）")
    ap.add_argument("--project-root", default=None, help="（可选）项目根，用于宿主 CI 门禁侦测")
    ap.add_argument("--json", default=None, help="（可选）报告输出为 JSON 文件路径")
    args = ap.parse_args()

    skills_dir = os.path.expanduser(args.skills_dir)
    report = {"skills_dir": skills_dir, "checks": [], "verdict": None}

    if not os.path.isdir(skills_dir):
        report["checks"].append({
            "code": "ENV-DIR",
            "level": "info",
            "detail": f"目标目录不存在（将新建，无覆盖风险）: {skills_dir}",
        })
    else:
        report["checks"].extend(check_dir_conflicts(skills_dir))
        report["checks"].extend(check_name_conflicts(skills_dir))

    report["checks"].extend(check_python())
    if args.project_root:
        root = os.path.expanduser(args.project_root)
        if os.path.isdir(root):
            report["checks"].extend(check_host_ci(root))
        else:
            report["checks"].append({"code": "ENV-DIR", "level": "environment",
                                     "detail": f"--project-root 不存在: {root}"})
    report["checks"].extend(check_network_mode())

    conflicts = [c for c in report["checks"] if c["level"] == "conflict"]
    envs = [c for c in report["checks"] if c["level"] == "environment"]
    report["verdict"] = "BLOCKED" if conflicts else ("ENV-ISSUE" if envs else "CLEAN")

    # ---- 人读输出 ----
    print("=" * 60)
    print("UCSS/UTSS 装前共存检测（coexist_check.py · 零写入）")
    print(f"  目标技能目录: {skills_dir}")
    if args.project_root:
        print(f"  项目根      : {os.path.expanduser(args.project_root)}")
    print("=" * 60)
    if not report["checks"]:
        print("  无任何发现。可直接安装（安装命令见各规范安装指南）。")
    for c in report["checks"]:
        icon = {"conflict": "❌", "environment": "⚠️ ", "info": "ℹ️ "}.get(c["level"], "·")
        print(f"\n{icon} [{c['code']}] {c['detail']}")
        for i, opt in enumerate(c.get("options", []), 1):
            print(f"     {opt}")
    print("\n" + "=" * 60)
    print(f"结论: {report['verdict']}"
          + ("（发现冲突——按选项消解后重检，禁止直接覆盖）" if conflicts else ""
             + ("（环境不满足）" if envs else "")))
    print("退出码: 0=可安装 / 1=冲突待消解 / 2=环境问题")

    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        print(f"JSON 报告已写出: {args.json}")

    return 1 if conflicts else (2 if envs else 0)


if __name__ == "__main__":
    sys.exit(main())
