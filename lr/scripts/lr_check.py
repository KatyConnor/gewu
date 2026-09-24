#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""lr_check.py — LR-STD-001 重构工程阶段门禁校验
用法：python3 lr_check.py [--root <项目根>] [--stage R0..R6] [--all]
校验组：
  G1 准入核查      —— docs/lpu/ 8 份文档 + rebuild-plan 存在（R0 前置）
  G2 阶段文档齐备  —— 当前/指定阶段必输文档存在且非空
  G3 模板章节完整  —— 文档包含模板规定的必要章节标题
  G4 状态机一致性  —— PROGRESS.md 阶段推进与文档产出互相印证
  G5 单元三件套    —— R4 已切换单元的方案/对照验证/切换记录齐备
  G6 变更追溯矩阵  —— D-R4-02 覆盖率与悬空映射检查（R4+ 阶段）
  G7 文档交叉引用  —— 文档内 .md 引用真实可达
  G8 AETC 协同     —— audit/traces 留痕存在性（软校验，有 audit/ 时执行）
退出码 0=PASS 1=FAIL；报告写入 .lr/lr-check-report.md
"""
import os
import re
import sys

ROOT = os.path.abspath(os.getcwd())
LPU_DOCS = ["overview.md", "architecture.md", "database.md", "features-api.md",
            "business-processes.md", "rules-glossary.md", "debt-risks.md", "rebuild-plan.md"]

# 每阶段必输文档（非单元级）；单元级由 G5 动态核查
STAGE_DOCS = {
    "R0": ["D-R0-01", "D-R0-02"],
    "R1": ["D-R1-01", "D-R1-02", "D-R1-03", "D-R1-04"],
    "R2": ["D-R2-01", "D-R2-02", "D-R2-03"],
    "R3": ["D-R3-01", "D-R3-02", "D-R3-03"],
    "R4": ["D-R4-01", "D-R4-02"],
    "R5": ["D-R5-01", "D-R5-02", "D-R5-03"],
    "R6": ["D-R6-01", "D-R6-02"],
}
STAGE_ORDER = ["R0", "R1", "R2", "R3", "R4", "R5", "R6"]

# 每文档必要章节（与 templates/ 对齐，取模板 ## 标题）
REQUIRED_SECTIONS = {
    "D-R0-01": ["决策摘要", "策略选择与理由", "范围与排除项", "审批"],
    "D-R0-02": ["核查结论", "文档齐备性", "待人工确认清单闭环"],
    "D-R1-01": ["分层与模块清单", "接口契约", "新旧并存层设计", "差距分析"],
    "D-R1-02": ["ADR 索引", "退出成本"],
    "D-R1-03": ["CR-RULES 引用", "测试策略"],
    "D-R1-04": ["目标 ERD", "字段映射初稿", "状态枚举映射终稿", "不可逆变更标注"],
    "D-R2-01": ["单元总表", "依赖图", "首批试点说明", "变更记录"],
    "D-R2-02": ["批次序列", "退役批"],
    "D-R2-03": ["风险总表", "无回退风险升级"],
    "D-R3-01": ["覆盖范围", "特征测试清单", "金样本清单", "双侧可执行性"],
    "D-R3-02": ["快照范围", "四层校验方案"],
    "D-R3-03": ["分批预案", "演练记录"],
    "D-R4-01": ["日志条目"],
    "D-R4-02": ["映射总表", "悬空检查", "覆盖率"],
    "D-R5-01": ["验收总结论", "功能验收", "遗留项登记"],
    "D-R5-02": ["四层终验结果"],
    "D-R5-03": ["归档内容清单", "下线序列"],
    "D-R6-01": ["偏差分析", "回滚事件复盘", "LPU 反馈"],
    "D-R6-02": ["总索引", "项目关闭声明"],
}

TEMPLATE_DIR = None
errors = warnings = 0
report = []


def check(gid, name, ok, detail=""):
    global errors
    status = "PASS" if ok else "FAIL"
    if not ok:
        errors += 1
    report.append(f"- [{status}] {gid} {name}" + (f" | {detail}" if detail else ""))
    return ok


def warn(gid, name, detail):
    global warnings
    report.append(f"- [WARN] {gid} {name} | {detail}")


def read(p):
    try:
        with open(p, encoding="utf-8") as f:
            return f.read()
    except OSError:
        return ""


def doc_path(root, code):
    return os.path.join(root, "docs", "lr", f"{code}.md")


def main():
    global TEMPLATE_DIR
    root = ROOT
    if "--root" in sys.argv:
        root = os.path.abspath(sys.argv[sys.argv.index("--root") + 1])
    TEMPLATE_DIR = os.path.join(root, "lr", "templates")
    docs_dir = os.path.join(root, "docs", "lr")
    lpu_dir = os.path.join(root, "docs", "lpu")
    prog = os.path.join(root, ".lr", "progress", "PROGRESS.md")

    report.append(f"# LR-STD-001 阶段门禁校验报告\n\n项目根：{root}\n")

    # ── G1 准入核查 ──
    if os.path.isdir(lpu_dir):
        missing = [d for d in LPU_DOCS if not os.path.isfile(os.path.join(lpu_dir, d))]
        check("G1", "LPU 成果准入（8 份文档 + rebuild-plan）", not missing,
              "缺失: " + ", ".join(missing) if missing else "")
    else:
        check("G1", "LPU 成果准入（docs/lpu/ 不存在）", False,
              "LR 以 LPU 产出为前置，请先完成 LPU-STD-001 流程")

    # ── 确定校验阶段集合 ──
    if "--all" in sys.argv:
        stages = STAGE_ORDER
    elif "--stage" in sys.argv:
        stages = [sys.argv[sys.argv.index("--stage") + 1]]
        if stages[0] not in STAGE_ORDER:
            print("非法阶段：", stages[0]); sys.exit(2)
    else:
        # 从 PROGRESS.md 推断当前阶段
        stages = []
        if os.path.isfile(prog):
            m = re.search(r"当前阶段[：:]\s*(R[0-6])", read(prog))
            if m:
                stages = [m.group(1)]
        if not stages:
            # 按已产出文档推断最深阶段
            for st in STAGE_ORDER:
                if all(os.path.isfile(doc_path(root, c)) for c in STAGE_DOCS[st]):
                    stages = [st]
            stages = stages or ["R0"]

    # ── G2 阶段文档齐备（含历史阶段不回退） ──
    cur_idx = STAGE_ORDER.index(stages[-1])
    for st in STAGE_ORDER[:cur_idx + 1]:
        missing = [c for c in STAGE_DOCS[st]
                   if not os.path.isfile(doc_path(root, c)) or
                   len(read(doc_path(root, c)).replace("【待填写】", "").strip()) < 50]
        check("G2", f"{st} 必输文档齐备且非空", not missing,
              "缺失/未填: " + ", ".join(missing) if missing else f"({len(STAGE_DOCS[st])} 份)")

    # ── G3 模板章节完整 ──
    for st in STAGE_ORDER[:cur_idx + 1]:
        for code in STAGE_DOCS[st]:
            p = doc_path(root, code)
            if not os.path.isfile(p):
                continue
            text = read(p)
            miss = [s for s in REQUIRED_SECTIONS.get(code, []) if s not in text]
            check("G3", f"{code} 章节完整", not miss, "缺: " + ", ".join(miss) if miss else "")

    # ── G4 状态机一致性 ──
    if os.path.isfile(prog):
        ptext = read(prog)
        m = re.search(r"当前阶段[：:]\s*(R[0-6])", ptext)
        if m:
            declared = m.group(1)
            # 声明的阶段，其前一阶段文档应齐备
            di = STAGE_ORDER.index(declared)
            prev_ok = all(os.path.isfile(doc_path(root, c))
                          for st in STAGE_ORDER[:di] for c in STAGE_DOCS[st])
            check("G4", f"PROGRESS 声明 {declared} 与文档产出一致", prev_ok,
                  "" if prev_ok else "前置阶段文档缺失而进度已推进——禁止跳阶段")
    else:
        warn("G4", "未找到 .lr/progress/PROGRESS.md", "首次执行请先初始化 .lr/（lr/init/ 复制）")

    # ── G5 单元三件套（R4 起） ──
    if cur_idx >= STAGE_ORDER.index("R4"):
        units = sorted(set(re.findall(r"D-R4-(U\d+)-01", " ".join(
            os.listdir(docs_dir) if os.path.isdir(docs_dir) else []))))
        if not units:
            warn("G5", "尚无单元文档", "R4 实施应从试点单元开始")
        for u in units:
            trip = [f"D-R4-{u}-{k}" for k in ("01", "02", "03")]
            miss = [c for c in trip if not os.path.isfile(doc_path(root, c))]
            if miss:
                # 02/03 缺失但 01 存在：单元尚在实施中 → 提示而非失败
                if not os.path.isfile(doc_path(root, trip[2])) and os.path.isfile(doc_path(root, trip[0])) \
                        and not os.path.isfile(doc_path(root, trip[1])):
                    warn("G5", f"单元 {u} 实施中", "方案已立，对照验证未出")
                else:
                    check("G5", f"单元 {u} 三件套", False, "缺: " + ", ".join(miss))
            else:
                check("G5", f"单元 {u} 三件套", True)

    # ── G6 变更追溯矩阵（R4 起） ──
    if cur_idx >= STAGE_ORDER.index("R4"):
        m6 = doc_path(root, "D-R4-02")
        if os.path.isfile(m6):
            t6 = read(m6)
            has_rows = len(re.findall(r"U\d+|M\d+", t6)) >= 3
            check("G6", "变更追溯矩阵有条目", has_rows,
                  "" if has_rows else "矩阵无单元级条目")
            if cur_idx >= STAGE_ORDER.index("R5"):
                cov = "100%" in t6 or "100 %" in t6
                check("G6", "矩阵覆盖率 100%（R5 前置）", cov,
                      "覆盖率未达 100% 或未标注——存在悬空映射即验收阻塞")
        else:
            check("G6", "变更追溯矩阵存在", False)

    # ── G7 文档交叉引用 ──
    if os.path.isdir(docs_dir):
        broken = []
        for fn in os.listdir(docs_dir):
            if not fn.endswith(".md"):
                continue
            text = read(os.path.join(docs_dir, fn))
            for m in re.finditer(r"`(docs/lpu/[\w\-./]+\.md|docs/lr/[\w\-./]+\.md|D-R[0-6]-[\w-]+\.md)`", text):
                t = m.group(1)
                if t.startswith("docs/"):
                    ok = os.path.isfile(os.path.join(root, t))
                else:
                    ok = os.path.isfile(os.path.join(docs_dir, t))
                if not ok:
                    broken.append(f"{fn} → {t}")
        check("G7", "文档交叉引用可达", not broken, "; ".join(broken[:5]) if broken else "")

    # ── G8 AETC 协同（软校验） ──
    audit_dir = os.path.join(root, "audit", "traces")
    if os.path.isdir(audit_dir):
        files = os.listdir(audit_dir)
        if files:
            check("G8", "AETC 留痕库非空", True, f"{len(files)} 条留痕")
        else:
            warn("G8", "audit/traces 为空", "LR 的 R2/R3 级操作应产生留痕")
    else:
        warn("G8", "未安装 AETC", "留痕管控未启用（建议与 AETC-STD-001 协同部署）")

    # ── 汇总 ──
    npass = len([l for l in report if "[PASS]" in l])
    report.insert(1, f"\n校验范围：{', '.join(stages)} | 结果：{'PASS ✅' if errors == 0 else 'FAIL ❌'}"
                     f" | {npass} 项通过 / {errors} 项失败 / {warnings} 提示\n")
    out = os.path.join(root, ".lr", "lr-check-report.md")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(report) + "\n")
    print("\n".join(report))
    print("\n报告:", out)
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
