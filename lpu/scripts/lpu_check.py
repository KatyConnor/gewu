#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
lpu_check.py — LPU 交付自检脚本（LPU-STD-001 V1.0 · 规则 LPU-DOC-001/002/003/006 落地）
用法:
    python3 lpu_check.py --docs docs/lpu/ --findings .lpu/findings/
    python3 lpu_check.py --docs docs/lpu/ --findings .lpu/findings/ --report check-report.md

检查项:
  G1 文档齐备性     —— 8 份标准文档存在且非空
  G2 模板章节完整性 —— 每份文档包含模板规定的必要章节
  G3 证据锚点格式   —— 代码/数据证据锚点 (路径:行号) 数量达标
  G4 置信度标注     —— [A]/[B]/[C] 标注存在且分布统计
  G5 C级结论隔离    —— [C] 结论不出现在 rebuild-plan 决策章节, 且汇总于待人工确认清单
  G6 文档交叉引用   —— 文档间 .md 引用真实可达
  G7 findings 库    —— 七维发现文件齐备, 条目带【证据】
退出码: 0=通过  1=不通过(FAIL)  2=用法错误
仅依赖 Python3 标准库。
"""
import argparse
import os
import re
import sys
from datetime import datetime

DOCS_REQUIRED = {
    "overview.md": ["## 太长不看", "## 1. 项目身份卡", "## 5. 全景结论"],
    "architecture.md": ["## 太长不看", "## 2. 模块依赖图", "## 4. 外部系统集成"],
    "database.md": ["## 太长不看", "## 2. 数据字典", "## 6. 脏数据与迁移风险"],
    "features-api.md": ["## 太长不看", "## 1. 功能清单", "## 5. 鉴权与权限模型"],
    "business-processes.md": ["## 太长不看", "## 1. 核心状态机", "## 3. 异常与补偿流程"],
    "rules-glossary.md": ["## 太长不看", "## 1. 领域术语表", "## 3. 隐式规则"],
    "debt-risks.md": ["## 太长不看", "## 1. 债务分级总览", "## 4. 死代码清单"],
    "rebuild-plan.md": ["## 太长不看", "## 1. 策略评估与推荐", "## 6. 待人工确认清单"],
}

FINDINGS_REQUIRED = ["RECON.md", "C-code.md", "D-data.md", "F-function.md",
                     "K-knowledge.md", "P-process.md", "X-conflicts.md"]

EVIDENCE_RE = re.compile(
    r"(?:[\w./\\\-]+\.(?:java|kt|py|js|ts|go|php|rb|cs|c|cpp|h|vue|jsx|tsx|sql|xml|yml|yaml|"
    r"properties|conf|ini|jsp|asp|ftl|html|sh|bat):\d+)|【证据】"
)
CONF_RE = re.compile(r"\[([ABC])\]")
MD_REF_RE = re.compile(r"\b([a-z][a-z0-9\-]*\.md)\b")
BR_RE = re.compile(r"\b(?:BR|IR|TD|QC|XF|F-[CDFKP])-?\d+\b")


def read(path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return f.read()
    except OSError:
        return None


class Result:
    def __init__(self):
        self.items = []  # (level, group, message)

    def ok(self, group, msg):
        self.items.append(("PASS", group, msg))

    def warn(self, group, msg):
        self.items.append(("WARN", group, msg))

    def fail(self, group, msg):
        self.items.append(("FAIL", group, msg))

    @property
    def failed(self):
        return any(l == "FAIL" for l, _, _ in self.items)

    def summary(self):
        n = {"PASS": 0, "WARN": 0, "FAIL": 0}
        for l, _, _ in self.items:
            n[l] += 1
        return n


def check_docs_presence(docs_dir, res):
    for name in DOCS_REQUIRED:
        p = os.path.join(docs_dir, name)
        content = read(p)
        if content is None:
            res.fail("G1 文档齐备性", f"缺失: {name}")
        elif len(content.strip()) < 100:
            res.fail("G1 文档齐备性", f"内容过短(<100字符): {name}")
    if not res.failed:
        res.ok("G1 文档齐备性", "8 份标准文档齐备且非空")


def check_sections(docs_dir, res):
    problems = 0
    for name, sections in DOCS_REQUIRED.items():
        content = read(os.path.join(docs_dir, name))
        if content is None:
            continue
        for sec in sections:
            if sec not in content:
                res.fail("G2 模板章节", f"{name} 缺少章节: {sec}")
                problems += 1
    if problems == 0:
        res.ok("G2 模板章节", "全部必要章节齐备")


def check_evidence(docs_dir, res):
    total = 0
    per_doc = {}
    for name in DOCS_REQUIRED:
        content = read(os.path.join(docs_dir, name))
        if content is None:
            continue
        hits = len(EVIDENCE_RE.findall(content))
        per_doc[name] = hits
        total += hits
        if name in ("architecture.md", "database.md", "features-api.md",
                    "rules-glossary.md", "debt-risks.md", "business-processes.md"):
            if hits == 0:
                res.fail("G3 证据锚点", f"{name} 未检出任何证据锚点 (路径:行号 / 【证据】)")
    if not res.failed:
        res.ok("G3 证据锚点", f"全库证据锚点 {total} 处: " +
               ", ".join(f"{k}={v}" for k, v in per_doc.items() if v))


def check_confidence(docs_dir, res):
    dist = {"A": 0, "B": 0, "C": 0}
    for name in DOCS_REQUIRED:
        content = read(os.path.join(docs_dir, name))
        if content is None:
            continue
        for m in CONF_RE.finditer(content):
            dist[m.group(1)] += 1
    if dist["A"] + dist["B"] + dist["C"] == 0:
        res.fail("G4 置信度标注", "全部文档未检出 [A]/[B]/[C] 置信度标注")
    elif dist["A"] == 0:
        res.warn("G4 置信度标注", f"A 级结论为 0 (A={dist['A']}/B={dist['B']}/C={dist['C']})，"
                                "可能证据锚定不足")
    else:
        res.ok("G4 置信度标注", f"A={dist['A']} / B={dist['B']} / C={dist['C']}")
    return dist


def check_c_isolation(docs_dir, dist, res):
    plan = read(os.path.join(docs_dir, "rebuild-plan.md"))
    if plan is None:
        return
    has_c_anywhere = dist["C"] > 0
    # 决策章节 = "## 1." 至 "## 6." 之前的内容
    m = re.search(r"(.*?)## 6\. 待人工确认清单", plan, re.S)
    decision_part = m.group(1) if m else plan
    c_in_decision = CONF_RE.findall(decision_part)
    if c_in_decision:
        res.fail("G5 C级隔离", f"rebuild-plan 决策章节(§1-§5)出现 {len(c_in_decision)} 处 [C] 标注")
    if has_c_anywhere:
        if "## 6. 待人工确认清单" not in plan:
            res.fail("G5 C级隔离", "存在 C 级结论但 rebuild-plan 缺少'待人工确认清单'章节")
        elif not BR_RE.search(plan.split("## 6. 待人工确认清单")[-1]):
            res.warn("G5 C级隔离", "待人工确认清单为空但全库存在 C 级结论，请核对是否已全部汇总")
        else:
            res.ok("G5 C级隔离", "C 级结论已隔离至待人工确认清单")
    else:
        res.ok("G5 C级隔离", "无 C 级结论（或已全部升级）")


def check_crossrefs(docs_dir, res):
    doc_names = set(DOCS_REQUIRED)
    refs_bad = []
    for name in DOCS_REQUIRED:
        content = read(os.path.join(docs_dir, name))
        if content is None:
            continue
        for ref in set(MD_REF_RE.findall(content)):
            if ref in doc_names and ref != name:
                if not os.path.exists(os.path.join(docs_dir, ref)):
                    refs_bad.append(f"{name} → {ref}")
    if refs_bad:
        res.fail("G6 交叉引用", "; ".join(refs_bad))
    else:
        res.ok("G6 交叉引用", "文档间 .md 引用全部可达")


def check_findings(findings_dir, res):
    missing = [f for f in FINDINGS_REQUIRED
               if read(os.path.join(findings_dir, f)) is None]
    if missing:
        res.fail("G7 findings库", "缺失: " + ", ".join(missing))
        return
    entry_re = re.compile(r"^### ", re.M)
    ev_re = EVIDENCE_RE
    stats = []
    for f in FINDINGS_REQUIRED:
        content = read(os.path.join(findings_dir, f))
        entries = entry_re.findall(content)
        ev = len(ev_re.findall(content))
        stats.append(f"{f}:{len(entries)}条/{ev}证据")
        if entries and ev < len(entries):
            res.warn("G7 findings库", f"{f} 存在无证据条目 (条目{len(entries)}/证据{ev})")
    res.ok("G7 findings库", "七维文件齐备 · " + " · ".join(stats))


def render_report(res, args, dist):
    lines = []
    lines.append("# LPU 交付自检报告")
    lines.append("")
    lines.append(f"- 时间: {datetime.now().isoformat(timespec='seconds')}")
    lines.append(f"- 文档目录: {args.docs}")
    lines.append(f"- findings 目录: {args.findings}")
    n = res.summary()
    verdict = "FAIL" if res.failed else ("PASS(有警告)" if n["WARN"] else "PASS")
    lines.append(f"- 结论: **{verdict}** (PASS {n['PASS']} / WARN {n['WARN']} / FAIL {n['FAIL']})")
    lines.append(f"- 置信度分布: A={dist['A']} / B={dist['B']} / C={dist['C']}")
    lines.append("")
    lines.append("| 级别 | 检查组 | 说明 |")
    lines.append("|---|---|---|")
    for level, group, msg in res.items:
        lines.append(f"| {level} | {group} | {msg} |")
    lines.append("")
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser(description="LPU 交付自检 (LPU-STD-001 V1.0)")
    ap.add_argument("--docs", required=True, help="成文文档目录 (docs/lpu/)")
    ap.add_argument("--findings", required=True, help="发现库目录 (.lpu/findings/)")
    ap.add_argument("--report", default=None, help="可选: 自检报告输出路径 (Markdown)")
    args = ap.parse_args()

    if not os.path.isdir(args.docs):
        print(f"[FAIL] 文档目录不存在: {args.docs}")
        sys.exit(1)
    if not os.path.isdir(args.findings):
        print(f"[FAIL] findings 目录不存在: {args.findings}")
        sys.exit(1)

    res = Result()
    check_docs_presence(args.docs, res)
    check_sections(args.docs, res)
    check_evidence(args.docs, res)
    dist = check_confidence(docs_dir=args.docs, res=res)
    check_c_isolation(args.docs, dist, res)
    check_crossrefs(args.docs, res)
    check_findings(args.findings, res)

    report = render_report(res, args, dist)
    print(report)
    if args.report:
        with open(args.report, "w", encoding="utf-8") as f:
            f.write(report)
        print(f"\n报告已写入: {args.report}")
    sys.exit(1 if res.failed else 0)


if __name__ == "__main__":
    main()
