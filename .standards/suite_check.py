#!/usr/bin/env python3
# -*- coding: utf-8 -*-
""".standards/suite_check.py — STD-HUB-001 统一调度体系安装校验
用法：python3 .standards/suite_check.py [--root <项目根>]
校验：G1 调度器存在与注册表路径 | G2 十三规范入口完整 | G3 规范本体目录与关键子文件
     | G4 交叉引用存在性 | G5 SDLC角色→技能引用闭环 | G6 触发词矩阵对齐 | G7 单规范可拆性
退出码 0=PASS 1=FAIL；报告写入 suite-check-report.md
"""
import json
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.getcwd())
report_lines = []
errors = 0
warnings = 0


def readf(p):
    return open(p, encoding="utf-8").read() if os.path.isfile(p) else ""

def check(gid, name, ok, detail=""):
    global errors, warnings
    status = "PASS" if ok else "FAIL"
    if not ok:
        errors += 1
    report_lines.append(f"- [{status}] {gid} {name}" + (f" | {detail}" if detail else ""))
    return ok


def main():
    root = ROOT
    if "--root" in sys.argv:
        root = os.path.abspath(sys.argv[sys.argv.index("--root") + 1])

    # 结构识别：若为安装包结构（而非安装后的项目根），给出引导
    if os.path.isdir(os.path.join(root, "02_统一调度入口")):
        print("检测到的是【安装包】结构，而非安装后的项目根。")
        print("本脚本校验安装结果。请先按 README 安装（复制 .standards/ 与 AGENTS.md 到项目根），再运行：")
        print("  python3 .standards/suite_check.py --root <项目根>")
        sys.exit(1)

    report_lines.append(f"# STD-HUB-001 安装校验报告\n\n项目根：{root}\n")

    # G0 运行环境底座（V1.9.28 跨环境批·RR-001/RPT-001 裁定）
    vi = sys.version_info
    check("G0", f"运行环境 Python>=3.6（实测 {vi.major}.{vi.minor}·universal_newlines 基线）", vi >= (3, 6))
    check("G0", "运行环境 Unix-like（posix·viewer fcntl 前置）", os.name == "posix")
    # G0 零隐式第三方 import 审计（S3 棘轮——rg/yaml 两次违纪固化防再犯）
    _STDLIB_OK = {"sys", "os", "re", "io", "json", "subprocess", "shutil", "time", "datetime",
                  "glob", "argparse", "tempfile", "hashlib", "unittest", "socket", "threading",
                  "zipfile", "http", "urllib", "socketserver", "webbrowser", "stat", "fnmatch",
                  "typing", "textwrap", "signal", "errno", "html", "ast", "contextlib",
                  "ctypes", "fcntl", "uuid", "difflib", "token", "tokenize", "traceback",
                  "platform", "string", "csv", "math", "random", "struct", "codecs", "base64",
                  # V1.9.30 收编 pdf-skill/luban-doc 技能包补齐（均标准库·技能包脚本使用）
                  "__future__", "dataclasses", "pathlib", "collections", "xml",
                  "colorsys", "importlib"}
    _LOCAL_OK = {"_mini_yaml", "adapters"}  # 本地模块放行集（新增本地模块须同步——审计失败会显式报出）
    # V1.9.30 技能包声明依赖放行集：技能包（luban-doc/pdf-skill）≠门禁链脚本——
    # 依赖在 SKILL.md compatibility +技能卡公开声明、装时知情；门禁链体系脚本仍零第三方（口径不松·新资产类新断言）
    _SKILL_PKG = {"luban-doc", "pdf-skill"}
    _SKILL_DEP_OK = {"reportlab", "pypdf", "fitz"}  # SKILL.md compatibility 声明过的运行时依赖
    _third = []
    _std_dir = os.path.join(root, ".standards")
    for dp, dns, fns in os.walk(_std_dir):
        if "guard" + os.sep + "integration" in dp:
            continue  # aegis 适配示例层（环境自担·REV-001 记档）
        in_skill_pkg = any(seg in _SKILL_PKG for seg in dp.split(os.sep))
        if in_skill_pkg and "assets" in dp.split(os.sep):
            continue  # 技能包 assets/=模板源文本（scaffold 拷出后才执行·非运行链脚本）
        for fn in fns:
            if fn.endswith(".py"):
                fp = os.path.join(dp, fn)
                for ln in open(fp, encoding="utf-8"):
                    m = re.match(r"^(?:import (\w+)|from (\w+)[.\s])", ln)
                    if m:
                        mod = m.group(1) or m.group(2)
                        allowed = mod in _STDLIB_OK or mod in _LOCAL_OK
                        if in_skill_pkg and mod in _SKILL_DEP_OK:
                            allowed = True  # 技能包声明依赖（公开声明面）
                        if mod and not allowed:
                            _third.append(f"{mod}@{os.path.relpath(fp, root)}")
    check("G0", "门禁链零隐式第三方 import（白名单审计·yaml 违纪 2026-10-16 防再犯）",
          not _third, "; ".join(_third[:5]) if _third else "")

    # G1 调度器
    disp_path = os.path.join(root, "AGENTS.md")
    g1 = os.path.isfile(disp_path)
    if g1:
        disp = open(disp_path, encoding="utf-8").read()
        g1 = "STD-HUB-001" in disp and "能力注册表" in disp
    check("G1", "统一调度器 AGENTS.md 存在且含注册表", g1)

    # G2 十规范入口
    entries = {
        ".standards/AETC.md": ["AETC-STD-001", "铁律"],
        ".standards/ATLAS.md": ["ATLAS", "横切型·对接", ".standards/atlas/ATLAS-RULES.md"],
        ".standards/LPU.md": ["LPU-STD-001", "五维"],
        ".standards/LR.md": ["LR-STD-001", "铁律"],
        ".standards/OPS.md": ["OPS-STD-001", "铁律"],
        ".standards/SDLC.md": ["角色", ".standards/skills/"],
        ".standards/AEGIS.md": ["AEGIS", "铁律", ".standards/aegis/AEGIS-RULES.md"],
        ".standards/code-review/RULES.md": ["L1", "L3"],
        ".standards/ui-design/UI-RULES.md": ["UI-STD-001", "五条最高法则"],
        ".standards/CODING.md": ["CODING-STD-001", "安全红线"],
        ".standards/META.md": ["STD-META-001", "质量门型·治理域", ".standards/meta/META-RULES.md"],
        ".standards/UCSS.md": ["UCSS", "质量门型·扫描域", ".standards/ucss/UCSS-RULES.md"],
        ".standards/UTSS.md": ["UTSS", "测试域", ".standards/utss/SKILL.md"],
        ".standards/THEMIS.md": ["THEMIS-STD-014", "评审类型矩阵", "独立性红线"],
    }
    for fn, anchors in entries.items():
        p = os.path.join(root, fn)
        ok = os.path.isfile(p)
        if ok:
            s = open(p, encoding="utf-8").read()
            ok = all(a in s for a in anchors)
        check("G2", f"规范入口 {fn}", ok)

    # G3 规范本体目录与关键子文件
    keyfiles = [
        ".standards/audit/rules/00-core.md", ".standards/audit/tools/audit_log.py", ".standards/audit/config.json",
        ".standards/atlas/ATLAS-RULES.md", ".standards/atlas/config.yaml",
        ".standards/atlas/schemas/trace-event.schema.json", ".standards/atlas/schemas/decision-ticket.schema.json",
        ".standards/atlas/scripts/atlas_check.py",
        ".standards/lpu/rules/00-core.md", ".standards/lpu/scripts/lpu_check.py", ".standards/lpu/init/config.json",
        ".standards/lr/rules/00-core.md", ".standards/lr/scripts/lr_check.py", ".standards/lr/templates/D-R0-01.md",
        ".standards/aegis/AEGIS-RULES.md", ".standards/aegis/templates/RC-template.yaml",
        ".standards/aegis/templates/FP-template.yaml", ".standards/aegis/templates/VR-template.yaml",
        ".standards/aegis/guard/aegis_guard.py", ".standards/aegis/scripts/aegis_check.py",
        ".standards/meta/META-RULES.md", ".standards/meta/templates/form-classification-record.yaml",
        ".standards/templates/README.md", ".standards/templates/TPL-SRS.md", ".standards/templates/TPL-FD.md",
        ".standards/meta/scripts/meta_check.py", ".standards/meta/records/存量判型清单V1.7.md",
        ".standards/meta/records/spec-ucss.yaml", ".standards/meta/records/spec-utss.yaml", ".standards/meta/records/spec-aetc.yaml",
        ".standards/themis/README.md", ".standards/themis/templates/RR-template.yaml",
        ".standards/themis/templates/FINDING-template.yaml", ".standards/themis/templates/RPT-template.yaml",
        ".standards/themis/templates/CAPA-template.yaml", ".standards/themis/records/REV-000-立项自审.md",
        ".standards/meta/records/spec-themis.yaml",
        ".standards/ucss/UCSS-RULES.md", ".standards/ucss/SKILL.md", ".standards/ucss/scripts/ucss_gate.py",
        ".standards/ucss/interop/tools/coexist_check.py", ".standards/ucss/interop/interop/severity_map.json",
        ".standards/utss/SKILL.md", ".standards/utss/references/05-rounds-gates.md", ".standards/utss/scripts/coverage_gate.py",
        ".standards/utss/templates/test-plan.md",
        ".standards/ui-design/UI-RULES.md", ".standards/ui-design/design-tokens.json", ".standards/ui-design/ai-antipatterns-checklist.md",
        ".standards/coding-rules/rules/00-general.md", ".standards/coding-rules/rules/07-sql.md", ".standards/coding-rules/configs/ruff.toml",
        ".standards/ops/rules/00-core.md", ".standards/ops/scripts/ops_check.py", ".standards/ops/templates/D-OPS-01.md",
        ".standards/roles/03-technical-architect.md", ".standards/skills/api-contract-definition.md",
        ".standards/code-review/project-config-template.json",
    ]
    for kf in keyfiles:
        check("G3", f"本体关键文件 {kf}", os.path.isfile(os.path.join(root, kf)))
    for d in ("audit", "atlas", "lpu", "lr", "ops", "roles", "skills", "aegis", "meta",
              "ucss", "utss", "code-review", "ui-design", "coding-rules", "dispatch", "themis"):
        check("G3", f"本体目录 .standards/{d}/", os.path.isdir(os.path.join(root, ".standards", d)))
    # G3 V1.8.21 首轮触发自检工具
    tc = os.path.join(root, ".standards", "dispatch", "trigger_check.py")
    check("G3", "本体关键文件 .standards/dispatch/trigger_check.py", os.path.isfile(tc))
    if os.path.isfile(tc):
        r = subprocess.run([sys.executable, tc, "--selftest"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, cwd=root)
        check("G1", "首轮触发自检 13 短语回归全过（调度表/路由表正本派生·V1.8.22 +外源接测短语）", r.returncode == 0)
        # V1.9.19 意图语义路由（无触发词例句→三答计分→唯一命中）
        im = os.path.join(root, "INTENT-MATRIX.md")
        check("G13", "INTENT-MATRIX.md 意图路由正本在位（安装平铺）", os.path.isfile(im))
        check("G13", "意图语义路由回归 8/8（词面盲区例句 UNIQUE 命中·INTENT-MATRIX 派生零漂移）",
              r.returncode == 0 and "意图 8/8" in r.stdout)
        # V1.9.20 协作链批
        im2 = os.path.join(root, "INTEROP-MATRIX.md")
        check("G14", "INTEROP-MATRIX.md 协作全景正本在位（安装平铺·第三层矩阵）", os.path.isfile(im2))
        check("G14", "协作链回归 4/4（主入口唯一命中+叠加⊇+顺序门⊇断言·链式推荐闭环）",
              r.returncode == 0 and "链 4/4" in r.stdout)
        # V1.9.21 交接物门禁批（缺口#2 收口）
        r2 = subprocess.run([sys.executable, ".standards/dispatch/handoff_check.py", "--selftest"],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, cwd=root)
        check("G15", "交接物门禁自测 PASS（注册表 7 项·6 场景翻转：空报/齐备/缺单/硬门正反/themis-rpt post-doc）",
              r2.returncode == 0 and "PASS" in r2.stdout)
        # V1.9.22 引擎批（缺口#3 收口）
        r3 = subprocess.run([sys.executable, ".standards/dispatch/sarif_dedup.py", "--selftest"],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, cwd=root)
        check("G16", "SARIF 去重键自测 PASS（cwe+file+line±3 互证·首配消耗语义·4 场景）",
              r3.returncode == 0 and "PASS" in r3.stdout)
        # V1.9.29 输入规范化批（REFINE 层·用户实报"输入描述混乱/重点不到位"→方案评审通过实施）
        im3 = os.path.join(root, "REFINE-MATRIX.md")
        check("G18", "REFINE-MATRIX.md 输入规范化正本在位（安装平铺·第四层矩阵）", os.path.isfile(im3))
        check("G18", "规范化回归 5/5（4 澄清缺集精确+1 齐备渲染对照·防过度澄清回缩）",
              r.returncode == 0 and "规范化 5/5" in r.stdout)
        rr = subprocess.run([sys.executable, tc, "--refine", "--spec", "AEGIS",
                             "--object", "登录接口报错", "--env", "测试环境登录服务",
                             "--done", "回归通过+三单留痕", "--artifact", "修复后代码"],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, cwd=root)
        check("G18", "--refine 齐备路径实跑（标准重写渲染+双文转发主文）",
              rr.returncode == 0 and "标准重写" in rr.stdout and "对象：登录接口报错" in rr.stdout)
        rm_ = subprocess.run([sys.executable, tc, "--refine", "--spec", "OPS",
                              "--object", "", "--env", "", "--done", "给出根因"],
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, cwd=root)
        rl = os.path.join(root, ".standards", "audit", "intent-log.jsonl")
        tail_refine = False
        if os.path.isfile(rl):
            last = open(rl, encoding="utf-8").read().strip().splitlines()[-1] if open(rl, encoding="utf-8").read().strip() else ""
            tail_refine = '"record_type": "refine"' in last
        check("G18", "--refine 缺失路径实跑（澄清问句+禁止瞎补+exit 4+落痕 record_type=refine）",
              rm_.returncode == 4 and "禁止瞎补" in rm_.stdout and tail_refine)
    # G17 THEMIS 评审审计域（V1.9.25 立项批·第十四套）
    th = readf(os.path.join(root, ".standards", "THEMIS.md"))
    check("G17", "THEMIS 入口在位（评审类型矩阵五类+独立性红线+IEEE 1028 对齐）",
          "THEMIS-STD-014" in th and "IEEE 1028" in th and "独立性红线" in th and "T5" in th)
    check("G17", "THEMIS 注册表接入（调度器 §一 第 14 行+三裁定分权裁决段）",
          "THEMIS-STD-014" in readf(disp_path) and "三裁定分权" in readf(disp_path))
    check("G17", "THEMIS 判型记录在位（第 14 份·双形态+独立性不变量）",
          os.path.isfile(os.path.join(root, ".standards", "meta", "records", "spec-themis.yaml")))
    check("G17", "THEMIS 首审四单齐（REV-000 dogfood：RR/FINDING/RPT/CAPA+三值结论+CAPA 义务）",
          os.path.isfile(os.path.join(root, ".standards", "themis", "records", "REV-000-立项自审.md")))
    rev0 = readf(os.path.join(root, ".standards", "themis", "records", "REV-000-立项自审.md"))
    check("G17", "首审三值结论与复审闭环（有条件通过→CAPA 清偿→closed·四单契约完整演练）",
          "有条件通过" in rev0 and "CAPA-000" in rev0 and "state: closed" in rev0 and "复审" in rev0)
    check("G17", "批2 欠账清偿显式（V1.9.26：rev_check+themis-rpt+词族+闭环注记在正本）",
          "已清偿" in th and "rev_check.py" in th)
    # V1.9.26 批2：rev_check 场景自测（空报/齐备/结论缺/状态非法）
    rc = os.path.join(root, ".standards", "themis", "scripts", "rev_check.py")
    check("G17", "rev_check.py 在位（G-T1~T4：结构/首审/单据/互锁）", os.path.isfile(rc))
    if os.path.isfile(rc):
        rrc = subprocess.run([sys.executable, rc, "--selftest"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, cwd=root)
        check("G17", "评审域门禁自测 PASS（4 场景翻转：空报/齐备过/结论缺报/状态非法报）",
              rrc.returncode == 0 and "PASS" in rrc.stdout)
    # V1.9.27 批3（methods 三件+度量采集）
    for mf, anchors in (("atam-lite.md", ["效用树", "敏感点", "权衡点", "场景走查表"]),
                        ("T4-audit.md", ["ISO 19011", "抽样", "R0", "红线"]),
                        ("T5-mgmt-review.md", ["输入", "CAPA", "措施决定"])):
        s_m = readf(os.path.join(root, ".standards", "themis", "methods", mf))
        check("G17", f"methods/{mf}（批3 深度·锚点齐）", bool(s_m) and all(x in s_m for x in anchors))
    mt = readf(os.path.join(root, ".standards", "themis", "records", "metrics-2026-10-15.md"))
    check("G17", "度量首采报告在位（--metrics 单据派生+n=1 如实标+不设基线）",
          "n=1" in mt and "不设基线" in mt and "--metrics" in mt)
    rrm = subprocess.run([sys.executable, ".standards/themis/scripts/rev_check.py", "--metrics"],
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, cwd=root)
    check("G17", "度量采集器实跑 PASS（RR 口径+红线 0+独立性违例计数）",
          rrm.returncode == 0 and "独立性违例（红线）：0" in rrm.stdout)
    # G19 语义直连与原文路由（V1.9.33 角色触发批·用户实测报障立项）
    # 根因链：语义引擎 split 十三版空转（objs/arts/feats 全废）+SDLC 语义入口过窄+角色卡零直连
    disp_txt = readf(disp_path)
    check("G19", "调度器 SDLC 行语义直连指令（命中即读角色卡·缺步违例）",
          "语义直连" in disp_txt and "三十秒版" in disp_txt)
    check("G19", "Step 0 每任务强制（V1.9.35 重构：MANDATORY+首条不豁免+长会话第 N 条不豁免）",
          "MANDATORY" in disp_txt and "长会话第 N 条" in disp_txt)
    check("G19", "语义先行+脚本降可选（L2 纯思考可执行·脚本不可用不阻塞调度）",
          "纯思考可执行" in disp_txt and "不依赖脚本执行" in disp_txt and "脚本不可用不阻塞调度" in disp_txt)
    check("G19", "落痕双轨（脚本轨 jsonl+对话轨显式输出——跑不了脚本不等于不留痕）",
          "双轨" in disp_txt and "跑不了脚本不等于不留痕" in disp_txt)
    import glob as _glob
    rfiles = sorted(_glob.glob(os.path.join(root, ".standards", "roles", "[01]*-*.md")))
    check("G19", "15 角色卡三十秒版头齐（Agent 单跳快读）",
          len(rfiles) == 15 and all("三十秒版" in readf(f) for f in rfiles))
    TEXT7 = [("帮我实现一个用户登录接口", "SDLC"), ("写个导出报表的功能", "SDLC"),
             ("这个模块加个缓存", "CODING"), ("设计一下订单表结构", "CODING"),
             ("帮我写个PRD", "SDLC"), ("部署脚本更新一下", "OPS"),
             ("登录接口报错修一下", "AEGIS")]
    ok7 = True
    for q, exp in TEXT7:
        rt = subprocess.run([sys.executable, tc, "--text", q], stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, universal_newlines=True, cwd=root)
        if not (rt.returncode == 0 and "唯一推荐" in rt.stdout and exp in rt.stdout):
            ok7 = False
    check("G19", "--text 原文直连 7/7（口语句全唯一命中：SDLC×3/CODING×2/OPS/AEGIS）", ok7)
    rt0 = subprocess.run([sys.executable, tc, "--text", "登录接口报错修一下"], stdout=subprocess.PIPE,
                         stderr=subprocess.PIPE, universal_newlines=True, cwd=root)
    check("G19", "落痕路径修复（无双 .standards 错位·FIX-C）",
          "落痕" in rt0.stdout and ".standards/.standards" not in rt0.stdout)
    rts = subprocess.run([sys.executable, tc, "--selftest"], stdout=subprocess.PIPE,
                         stderr=subprocess.PIPE, universal_newlines=True, cwd=root)
    check("G19", "selftest 全过（词面13+意图8+链4+规范化5——语义引擎修复不破回归）",
          rts.returncode == 0 and "通过" in rts.stdout)
    # G20 体系链路监控（V1.9.34 · AETC 域扩展：trace-log 五类事件+漏斗排查）
    tl = os.path.join(root, ".standards", "dispatch", "trace_log.py")
    check("G20", "trace_log.py 在位（五类事件模型+三态锚定复用）", os.path.isfile(tl))
    if os.path.isfile(tl):
        rp = subprocess.run([sys.executable, tl, "--report"], stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, universal_newlines=True, cwd=root)
        check("G20", "--report 实跑（事件分布+漏斗断裂率——排查入口可用）",
              rp.returncode == 0 and "链路漏斗" in rp.stdout)
        ev = subprocess.run([sys.executable, tl, "--event", "entry_read", "--spec", "SDLC"],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, cwd=root)
        ev2 = subprocess.run([sys.executable, tl, "--event", "bad_type"], stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, universal_newlines=True, cwd=root)
        check("G20", "事件协议双验（合法事件写入+非法类型拒写——schema 纪律）",
              ev.returncode == 0 and "已记" in ev.stdout and ev2.returncode != 0)
        rt2 = subprocess.run([sys.executable, tc, "--text", "登录接口报错修一下"], stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, universal_newlines=True, cwd=root)
        tl_rows = [json.loads(l) for l in open(os.path.join(root, ".standards", "audit", "trace-log.jsonl"),
                                               encoding="utf-8") if l.strip()] if os.path.isfile(os.path.join(root, ".standards", "audit", "trace-log.jsonl")) else []
        check("G20", "route 事件随路由自动写（机器强制层接线——G19 行为断言的监控化）",
              any(r.get("event") == "route" and r.get("spec") == "AEGIS" for r in tl_rows[-5:]))
    aetc_v = readf(os.path.join(root, ".standards", "AETC.md"))
    check("G20", "AETC §11 链路监控章在位（自报协议+违例语义+漏斗判读表）",
          "体系链路监控" in aetc_v and "entry_read" in aetc_v and "断裂率" in aetc_v)
    check("G20", "trace source 分家+三口径保守默认（V1.9.36：agent 显式/manual-test 默认/install）",
          "source" in readf(tl) and "manual-test" in readf(tl) and "宁可漏计不可虚计" in readf(tl))
    check("G20", "AETC §11.1 任务级一报+对话轨（逐次自报降频——多跳成本修正）",
          "任务级" in aetc_v and "对话即留痕" in aetc_v)
    inst = ""
    for _ip in (os.path.join(root, "install.sh"), os.path.join(root, "..", "install.sh"),
                os.path.join(root, "..", "..", "install.sh"),
                os.path.abspath(os.path.join(ROOT, "..", "..", "install.sh"))):  # V1.9.36 包根锚：发版门禁时版本戳断言可达
        if os.path.isfile(_ip):
            inst = readf(_ip)
            break
    if inst:
        check("G20", "install.sh 装痕分家打标（install_selftest 事件·装痕≠流痕判读依据）",
              "mark-install" in inst)
    else:
        check("G20", "install.sh 装痕打标（安装布局无此文件·跳过——开发仓/包面必验）", True)

    # G21 判定即读闭环批（V1.9.36 · 判型：10-24 黄金测试四证+trace dump 三证——
    # Agent 四轮任务「自报路由、从未读取」；route 11 条实为装后批量验证；装 35 记 34）
    ags = readf(disp_path)  # AGENTS.md 总调度入口
    check("G21", "Step 0 判定即读（判定说出口=入口读取已完成·先说后读=违例）",
          "判定说出口" in ags and "说而不读" in ags)
    check("G21", "对话轨三段式判定句（已读字段必填：命中 X → 已读入口+角色卡 → 留痕）",
          "已读" in ags and "三十秒版" in ags and "缺即违例" in ags)
    check("G21", "判定落痕含 --read-entry 用法（一条命令完成判定+读痕·自报摩擦归零）",
          "--read-entry" in ags and "--read-entry" in readf(tc))
    # 绑定实跑：--text 判定 + --read-entry → trace 尾部 route 与 entry_read 同链
    rte = subprocess.run([sys.executable, tc, "--text", "部署脚本更新一下", "--read-entry"],
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, cwd=root)
    tl_rows2 = [json.loads(l) for l in open(os.path.join(root, ".standards", "audit", "trace-log.jsonl"),
                                            encoding="utf-8") if l.strip()] if os.path.isfile(os.path.join(root, ".standards", "audit", "trace-log.jsonl")) else []
    check("G21", "判定-读取同链实跑（--read-entry → route+entry_read 相邻同 spec 落记）",
          rte.returncode == 0 and any(
              tl_rows2[i].get("event") == "route" and tl_rows2[i + 1].get("event") == "entry_read"
              and tl_rows2[i].get("spec") == tl_rows2[i + 1].get("spec")
              for i in range(max(0, len(tl_rows2) - 4), len(tl_rows2) - 1)))
    check("G21", "trace 口径声明在位（机器轨诊断不作会话证据·黄金测试为真判据）",
          "机器轨诊断" in readf(tl) and "黄金测试" in readf(tl))
    # 版本戳一致性（同族三犯根治：install.sh 硬编码 V1.9.34 装 35 记 34 + global 漏打装痕）
    import re as _re
    _pack_anchor = os.path.abspath(os.path.join(ROOT, "..", "..", "install.sh"))  # V1.9.36 包根锚（开发仓形态可达；安装形态不存在=无害）
    if inst:
        _bad = _re.findall(r'--mark-install "V[0-9]+\.[0-9]+\.[0-9]+"', inst)
        check("G21", "install.sh 版本戳零硬编码（读 MANIFEST 头行单一正本·装 N 记 N）",
              not _bad and "FILES_MANIFEST.txt" in inst)
    instg = ""
    for _ig in (os.path.join(root, "install-global.sh"), os.path.join(root, "..", "install-global.sh"),
                os.path.join(root, "..", "..", "install-global.sh"),
                os.path.join(os.path.dirname(_pack_anchor), "install-global.sh")):
        if os.path.isfile(_ig):
            instg = readf(_ig)
            break
    if instg:
        check("G21", "install-global.sh 补装痕打标（V1.9.36 欠账补齐·同读 MANIFEST 单一正本）",
              "mark-install" in instg and "FILES_MANIFEST.txt" in instg
              and not _re.findall(r'--mark-install "V[0-9]+\.[0-9]+\.[0-9]+"', instg))

    # G10 外源接测（V1.8.22 · UTSS V1.2 rev-spec-utss-v1.2）
    utss = readf(os.path.join(root, ".standards", "UTSS.md"))
    check("G10", "UTSS V1.2 含外源接测触发面与 §三·B 协议段（S1-S7）",
          "外源接测" in utss and "S7" in utss and "三·B" in utss)
    bev = readf(os.path.join(root, ".standards", "skills", "browser-e2e-validation.md"))
    check("G10", "browser-e2e-validation 技能存在", bool(bev))
    check("G10", "browser-e2e-validation 含凭据三禁红线（C1-C3 不落留痕/报告/用例）",
          "禁入留痕" in bev and "禁入报告" in bev and "C3" in bev)
    tcg = readf(os.path.join(root, ".standards", "skills", "test-case-generation.md"))
    check("G10", "test-case-generation V2.2 含 §9.6 模糊度三档派生", "9.6" in tcg and "exploratory" in tcg)
    # G10 V1.9.30 luban-doc 文档工程技能收编批（判型记录 form-classification-luban-doc-v1.0·技能形态·全局级）
    ldk = readf(os.path.join(root, ".standards", "skills", "luban-doc.md"))
    check("G10", "luban-doc 技能卡在位（六步法+五铁律+依赖三态探测声明）",
          bool(ldk) and "三态探测" in ldk and "merge_qa" in ldk and "六步" in ldk)
    fcr = readf(os.path.join(root, ".standards", "meta", "records", "form-classification-luban-doc-v1.0.yaml"))
    check("G10", "luban-doc 判型记录在位（技能/全局级/正交证据/能力边界诚实声明·docx 扩线观察项）",
          bool(fcr) and "form: skill" in fcr and "level: global" in fcr
          and "document-visual-design" in fcr and "scope_note" in fcr)
    lb_pkg = os.path.join(root, ".standards", "luban-doc")
    pdf_pkg = os.path.join(root, ".standards", "pdf-skill")
    check("G10", "P3 完整技能包双件在位（luban-doc 管线包+pdf-skill 依赖包·scripts 校验器齐）",
          os.path.isfile(os.path.join(lb_pkg, "scripts", "scaffold.py"))
          and os.path.isfile(os.path.join(lb_pkg, "scripts", "merge_qa.py"))
          and os.path.isfile(os.path.join(pdf_pkg, "scripts", "pdf_qa.py"))
          and os.path.isfile(os.path.join(pdf_pkg, "scripts", "pdf.py")))
    # G10 V1.9.0 P2 全局独立形态（两级探测）
    ag2 = readf(os.path.join(root, "AGENTS.md"))
    check("G10", "调度器含两级探测声明（P2 全局形态：项目根优先→~/.std-hub 兜底）",
          "两级探测" in ag2 and "std-hub" in ag2)
    # G10 V1.9.1 全局安装平台矩阵（包级资产：04 目录在场才验——P1 项目安装不含 04，矩阵质量归 release 链③b 包级校验）
    ig_path = os.path.join(root, "04_平台适配", "global", "README.md")
    if os.path.isfile(ig_path):
        ig = readf(ig_path)
        n_plat = sum(1 for x in ("claude-code", "opencode", "codex", "zcode", "gemini-cli", "copilot", "cline", "roo", "windsurf", "generic") if x in ig)
        check("G10", "全局平台矩阵 ≥10 平台（V1.9.2：+zcode）", n_plat >= 10)


    # G11 留痕产物查看器（V1.9.4 · STD-HUB-VIEW-001 一期静态单文件——工具件，adapters 契约/只读/零依赖）
    vw = os.path.join(root, ".standards", "viewer")
    vw_files = ["build.py", "selfcheck.py", "template/shell.html"] + [
        f"adapters/{m}_scan.py" for m in ("trace", "lpu", "task", "test", "meta", "prog")]
    vw_missing = [f for f in vw_files if not os.path.isfile(os.path.join(vw, f))]
    check("G11", "viewer 资产在场（build/selfcheck/shell+六 scanner，声明=目录双向）",
          not vw_missing)
    if not vw_missing:
        r = subprocess.run([sys.executable, os.path.join(vw, "build.py"), "--dryrun", "--project", root],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=60)
        dry_ok = '"schema_version": "1.0"' in r.stdout and r.returncode == 0
        check("G11", "viewer --dryrun 可跑（退出 0 + schema_version 输出）", dry_ok)
        import ast as _ast
        bad = []
        for f in vw_files:
            if not f.endswith(".py"):
                continue
            tree = _ast.parse(open(os.path.join(vw, f), encoding="utf-8").read())
            for n in _ast.walk(tree):
                mods = []
                if isinstance(n, _ast.Import):
                    mods = [a.name.split(".")[0] for a in n.names]
                elif isinstance(n, _ast.ImportFrom) and n.module:
                    mods = [n.module.split(".")[0]]
                for m in mods:
                    if m not in ("os", "sys", "json", "re", "argparse", "datetime", "pathlib",
                                 "hashlib", "collections", "typing", "functools", "itertools",
                                 "time", "shutil", "glob", "ast", "http", "fcntl", "msvcrt", "signal",
                                 "subprocess", "urllib", "unittest", "tempfile",
                                 "ctypes", "threading", "select", "struct", "queue",
                                 "adapters", "selfcheck"):
                        bad.append(f"{f}:{m}")
        check("G11", "viewer stdlib-only（import 白名单审计，S4 零三方依赖承诺）", not bad)
    # G12 UCSS-PyScan 自研引擎（V1.9.16·三级降级链 L3 兜底盘）
    ps_dir = os.path.join(root, ".standards", "ucss", "scripts")
    ps_files = ["pyscan.py", "ucss-sync.py", "ucss_gate.py"]
    ps_missing = [f for f in ps_files if not os.path.isfile(os.path.join(ps_dir, f))]
    check("G12", "UCSS-PyScan 引擎资产在场（pyscan/ucss-sync/gate）", not ps_missing,
          ",".join(ps_missing) if ps_missing else "")
    if not ps_missing:
        r = subprocess.run([sys.executable, os.path.join(ps_dir, "pyscan.py"), "selftest"],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=60)
        st_ok = r.returncode == 0 and "SELFTEST PASS" in r.stdout and "规则总数     : 64" in r.stdout
        # V1.9.31 可诊断性（用户麒麟 3.8.10 实报）：FAIL 必带原因摘要（退出码+selftest 输出尾部）
        st_reason = ""
        if not st_ok:
            tail = (r.stdout or r.stderr or "").strip().split("\n")
            st_reason = f"exit={r.returncode} | {' / '.join(tail[-3:])[:160]}"
        check("G12", "pyscan selftest 可跑（退出 0 + 四面全过 + 棘轮底 64 规则=56 py+8 js）", st_ok, st_reason)
        import ast as _ast2
        bad_ps = []
        for f in ps_files[:2]:
            tree = _ast2.parse(open(os.path.join(ps_dir, f), encoding="utf-8").read())
            for n in _ast2.walk(tree):
                mods = []
                if isinstance(n, _ast2.Import):
                    mods = [a.name.split(".")[0] for a in n.names]
                elif isinstance(n, _ast2.ImportFrom) and n.module:
                    mods = [n.module.split(".")[0]]
                for m in mods:
                    if m not in ("argparse", "ast", "json", "os", "re", "sys",
                                 "tempfile", "urllib", "io", "time", "zipfile", "glob"):
                        bad_ps.append(f"{f}:{m}")
        check("G12", "pyscan/ucss-sync stdlib-only（S4 零三方依赖·import 白名单审计）", not bad_ps,
              ",".join(bad_ps[:3]) if bad_ps else "")
    check("G12", "判型记录 rev-spec-ucss-v1.2.yaml 在场（V1.9.16 升版判型）",
          os.path.isfile(os.path.join(root, ".standards", "meta", "records", "rev-spec-ucss-v1.2.yaml")))
    # G11 V1.9.8 常驻监控（VIEW-002 Phase 1：daemon_ctl/daemon+GATE 纪律+调度卡注入+注册表幂等）
    ctl = os.path.join(vw, "daemon_ctl.py")
    dmn = os.path.join(vw, "daemon.py")
    check("G11", "daemon_ctl.py 与 daemon.py 在场（控制面/数据面分离 D3）",
          os.path.isfile(ctl) and os.path.isfile(dmn))
    ctl_src = readf(ctl) if os.path.isfile(ctl) else ""
    check("G11", "GATE 纪律：ctl 含 approved 豁免分支+GATE_REQUEST 申报（无静默拉起路径）",
          "approved" in ctl_src and "GATE_REQUEST" in ctl_src and "--approve" in ctl_src)
    ag = os.path.join(root, "AGENTS.md")
    ag_src = readf(ag) if os.path.isfile(ag) else ""
    gwg = os.path.join(root, "04_平台适配", "global", "GLOBAL-AGENTS.md")
    gwg_src = readf(gwg) if os.path.isfile(gwg) else ""
    if gwg_src:  # 包级资产在场才验（P1 项目安装不含 04_平台适配——V1.9.1 G10 同原则）
        check("G11", "调度卡 Step 0.5 注入（02 入口+global 全局卡·register 指令+GATE 引用+禁静默拉起）",
              "daemon_ctl.py register" in ag_src and "GATE" in ag_src
              and "daemon_ctl.py register" in gwg_src and "禁止静默拉起" in gwg_src)
    else:
        check("G11", "调度卡 Step 0.5 注入（02 入口·P1 安装形态）",
              "daemon_ctl.py register" in ag_src and "GATE" in ag_src)
    import tempfile as _tf, subprocess as _sp, shutil as _sh
    _fake = _tf.mkdtemp(prefix="g11-reg-")
    _env = dict(os.environ, HOME=_fake)
    _proj = os.path.join(_fake, "p1")
    os.makedirs(_proj, exist_ok=True)
    _ok = True
    try:
        for _ in range(3):
            _sp.run([sys.executable, ctl, "register", _proj], stdout=_sp.PIPE, stderr=_sp.PIPE, env=_env, timeout=30)
        _rows = [l for l in open(os.path.join(_fake, ".std-hub", "registry", "projects.list"),
                                 encoding="utf-8").read().splitlines() if l.strip()]
        _ok = _rows == [_proj]
    except Exception:
        _ok = False
    finally:
        _sh.rmtree(_fake, ignore_errors=True)
    check("G11", "注册表幂等自测（3 次 register=1 行·隔离 HOME）", _ok)
    wch = os.path.join(vw, "watcher.py")
    check("G11", "watcher.py 在场（Phase 2 推送：三后端+降级链）", os.path.isfile(wch))
    bsrc = readf(os.path.join(vw, "build.py")) if os.path.isfile(os.path.join(vw, "build.py")) else ""
    ssrc = readf(os.path.join(vw, "template", "shell.html")) if os.path.isfile(os.path.join(vw, "template", "shell.html")) else ""
    check("G11", "推送通道纪律（text/event-stream+/vm.json+EventSource 前端·推失效不推数据）",
          "text/event-stream" in bsrc and "/vm.json" in bsrc
          and "EventSource" in ssrc and "invalidate" in bsrc)

    # G4 交叉引用存在性（入口文件声明的路径都能解析）
    ref_patterns = [
        re.compile(r"`(.standards/audit/rules/[\w\-./]+\.md)`"),
        re.compile(r"`(.standards/atlas/[\w\-./]+\.(?:md|yaml|json|py))`"),
        re.compile(r"`(.standards/lpu/rules/[\w\-./]+\.md)`"),
        re.compile(r"`(.standards/lr/(?:rules|templates)/[\w\-./]+\.md)`"),
        re.compile(r"`(.standards/ops/(?:rules|templates)/[\w\-./]+\.md)`"),
        re.compile(r"`(.standards/aegis/[\w\-./]+\.(?:md|yaml|py))`"),
        re.compile(r"`(.standards/meta/[\w\-./]+\.(?:md|yaml|py))`"),
        re.compile(r"`(.standards/ucss/[\w\-./]+\.(?:md|py|json|yaml))`"),
        re.compile(r"`(.standards/utss/[\w\-./]+\.(?:md|py|json|yaml))`"),
        re.compile(r"`(.standards/roles/[\w\-./]+\.md)`"),
        re.compile(r"`(.standards/skills/[\w\-./]+\.md)`"),
        re.compile(r"`(.standards/code-review/[\w\-./]+\.(?:md|json))`"),
    ]
    broken = []
    for fn in ("AGENTS.md", ".standards/AETC.md", ".standards/ATLAS.md", ".standards/LPU.md", ".standards/LR.md", ".standards/OPS.md",
               ".standards/SDLC.md", ".standards/AEGIS.md", ".standards/CODING.md", ".standards/META.md", ".standards/UCSS.md", ".standards/UTSS.md"):
        p = os.path.join(root, fn)
        if not os.path.isfile(p):
            continue
        s = open(p, encoding="utf-8").read()
        for pat in ref_patterns:
            for m in pat.findall(s):
                if not os.path.exists(os.path.join(root, m)):
                    broken.append(f"{fn} → {m}")
    check("G4", "入口文件交叉引用全部可达", not broken, "; ".join(broken[:5]) if broken else "")

    # G5 SDLC 角色→技能引用闭环
    roles_dir = os.path.join(root, ".standards", "roles")
    missing_skill = []
    if os.path.isdir(roles_dir):
        for rf in sorted(os.listdir(roles_dir)):
            if not rf.endswith(".md"):
                continue
            s = open(os.path.join(roles_dir, rf), encoding="utf-8").read()
            for m in re.findall(r".standards/skills/([\w\-]+\.md)", s):
                if not os.path.isfile(os.path.join(root, ".standards", "skills", m)):
                    missing_skill.append(f"{rf} → .standards/skills/{m}")
    check("G5", "SDLC 角色→技能引用闭环", not missing_skill, "; ".join(missing_skill[:5]) if missing_skill else "")

    # G6 调度器注册表与入口文件一致
    g6 = g1
    if g6:
        for fn in (".standards/AETC.md", ".standards/ATLAS.md", ".standards/LPU.md", ".standards/LR.md", ".standards/OPS.md", ".standards/SDLC.md", ".standards/AEGIS.md",
                   ".standards/META.md", ".standards/UCSS.md", ".standards/UTSS.md", ".standards/code-review/RULES.md",
                   ".standards/ui-design/UI-RULES.md", ".standards/CODING.md"):
            if fn not in disp:
                g6 = False
                report_lines.append(f"  - 调度器注册表缺少 {fn}")
    check("G6", "调度器注册表覆盖全部规范入口", g6)

    # G7 单规范可拆性（每个规范独立成体：入口+本体目录配对）
    pairs = [(".standards/AETC.md", ".standards/audit"), (".standards/ATLAS.md", ".standards/atlas"), (".standards/LPU.md", ".standards/lpu"), (".standards/LR.md", ".standards/lr"), (".standards/OPS.md", ".standards/ops"), (".standards/SDLC.md", ".standards/roles"),
             (".standards/AEGIS.md", ".standards/aegis"), (".standards/META.md", ".standards/meta"), (".standards/UCSS.md", ".standards/ucss"), (".standards/UTSS.md", ".standards/utss"),
             (".standards/code-review/RULES.md", ".standards/code-review"), (".standards/ui-design/UI-RULES.md", ".standards/ui-design"),
             (".standards/CODING.md", ".standards/coding-rules")]
    for ent, body in pairs:
        ok = os.path.isfile(os.path.join(root, ent)) and os.path.isdir(os.path.join(root, body))
        check("G7", f"规范配对完整 {ent} ↔ {body}/", ok)

    # G8 SDLC 域内扩展（V1.1 +11号 / V1.2 +12号 / V1.3 +13号与阶段六 / V1.4 +14号）
    ext_roles = [
        ("11-interaction-ux-designer", "职责边界声明", "三重自检",
         ["ux-wireframe-prototyping", "design-system-tokens", "usability-heuristics-review"]),
        ("12-technical-writer", "职责边界声明", "Diátaxis",
         ["api-doc-generation", "user-manual-authoring", "docs-as-code-pipeline"]),
        ("13-product-ops-growth", "职责边界声明", "AARRR",
         ["growth-funnel-diagnosis", "user-segmentation-targeting", "growth-experiment-design"]),
        ("14-customer-success", "职责边界声明", "健康分",
         ["customer-onboarding-playbook", "account-health-monitoring", "renewal-expansion-management"]),
    ]
    for card, a1, a2, skills in ext_roles:
        cpath = os.path.join(root, ".standards/roles", card + ".md")
        tcard = open(cpath, encoding="utf-8").read() if os.path.isfile(cpath) else ""
        check("G8", f"角色卡 {card}.md 存在", bool(tcard))
        check("G8", f"角色卡 {card} 含边界声明与结构锚点",
              a1 in tcard and a2 in tcard and "RISK-01" in tcard and "RISK-08" in tcard)
        missing_ext = [s for s in skills
                       if not os.path.isfile(os.path.join(root, ".standards/skills", s + ".md"))]
        check("G8", f"{card} 绑定三技能文件存在", not missing_ext,
              f"缺: {missing_ext}" if missing_ext else "")
    sdlc_md = os.path.join(root, ".standards/SDLC.md")
    sdlc_text = open(sdlc_md, encoding="utf-8").read() if os.path.isfile(sdlc_md) else ""
    # 计数实数派生（V1.8.12 根治：断言数字从文件系统与 0.1 表运行时解析，消灭版本耦合——
    # 声明计数=目录实数=表行数三方一致；表↔文件双向无孤儿/悬挂；后续增删卡/技能零改断言）
    roles_actual = sorted(f[:-3] for f in os.listdir(roles_dir) if f.endswith(".md")) if os.path.isdir(roles_dir) else []
    skills_dir = os.path.join(root, ".standards", "skills")
    skills_actual = sorted(f[:-3] for f in os.listdir(skills_dir) if f.endswith(".md")) if os.path.isdir(skills_dir) else []
    declared = re.search(r"(\d+)\s*个阶段、(\d+)\s*个角色、(\d+)\s*个可复用技能模块", sdlc_text)
    row_re = re.compile(r"^\|\s*(?:\*\*(.+?)\*\*|)\s*\|\s*(\d{2})\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|$")
    table_ids, table_skills, stage_seq = [], [], []
    for line in sdlc_text.split("\n"):
        m = row_re.match(line.strip())
        if m:
            stg, rid, rname, slist = m.groups()
            if rname.strip() == "角色":
                continue
            if stg:
                stage_seq.append(stg)
            table_ids.append(rid)
            table_skills += [x.strip() for x in slist.split(",") if x.strip()]
    cnt_detail = ""
    cnt_ok = bool(declared) and bool(roles_actual) and bool(skills_actual)
    if cnt_ok:
        d_st, d_ro, d_sk = (int(x) for x in declared.groups())
        cnt_ok = (d_st == len(set(stage_seq)) and d_ro == len(roles_actual) and d_sk == len(skills_actual))
        cnt_detail = f"声明 {d_st}/{d_ro}/{d_sk} vs 实数 {len(set(stage_seq))}/{len(roles_actual)}/{len(skills_actual)}"
    check("G8", "SDLC.md 声明计数=实数派生（阶段/角色/技能三方一致）", cnt_ok, cnt_detail)
    card_ids = {r[:2] for r in roles_actual}
    hang_rows = sorted(set(table_ids) - card_ids)
    orphan_cards = sorted(card_ids - set(table_ids))
    check("G8", "0.1 表行与 roles/ 卡文件双向一致（无孤儿卡/悬挂行）",
          not hang_rows and not orphan_cards,
          f"悬挂行:{hang_rows} 孤儿卡:{orphan_cards}" if (hang_rows or orphan_cards) else "")
    hang_skills = sorted(set(table_skills) - set(skills_actual))
    orphan_skills = sorted(set(skills_actual) - set(table_skills))
    check("G8", "0.1 表技能清单与 skills/ 文件双向一致（无孤儿/悬挂技能）",
          not hang_skills and not orphan_skills,
          f"悬挂:{hang_skills[:3]} 孤儿:{orphan_skills[:3]}" if (hang_skills or orphan_skills) else "")
    check("G8", "SDLC.md 映射表含 11~14 号行（扩展角色在场）",
          "交互与体验设计师" in sdlc_text and "技术写作与文档工程师" in sdlc_text
          and "产品运营与增长专家" in sdlc_text and "客户成功专家" in sdlc_text
          and "六、运营与增长" in sdlc_text)
    # G9 判型记录↔本体引用双向（V1.8.19 实数派生根治：替代 G8/G9 两处硬编码元组——
    # 记录清单从 SDLC.md/META.md 本体引用运行时派生：本体引用的记录必须在场（引用可达），
    # rev-spec-sdlc-*/meta-* 记录必须被本体引用（无孤儿）——新版本注记即自动纳管，零改断言）
    recs_dir = os.path.join(root, ".standards", "meta", "records")
    recs_all = set(os.listdir(recs_dir)) if os.path.isdir(recs_dir) else set()
    meta_md_text = open(os.path.join(root, ".standards", "META.md"), encoding="utf-8").read() \
        if os.path.isfile(os.path.join(root, ".standards", "META.md")) else ""
    body_refs = set(re.findall(r"rev-spec-[a-z]+-v[0-9.]+\.yaml", sdlc_text + meta_md_text))
    miss_recs = sorted(body_refs - recs_all)
    check("G9", "本体引用的 rev 判型记录全部在场（引用可达，实数派生）",
          not miss_recs and bool(body_refs),
          f"缺: {miss_recs}" if miss_recs else f"引用 {len(body_refs)} 份")
    owned_recs = {r for r in recs_all
                  if r.startswith(("rev-spec-sdlc-", "rev-spec-meta-"))}
    orphan_recs = sorted(owned_recs - body_refs)
    check("G9", "rev-spec-sdlc-*/meta-* 记录无孤儿（均被本体版本注记引用）",
          not orphan_recs, f"孤儿: {orphan_recs}" if orphan_recs else "")
    disp_text = open(os.path.join(root, "AGENTS.md"), encoding="utf-8").read() if os.path.isfile(
        os.path.join(root, "AGENTS.md")) else ""
    check("G8", "调度器 SDLC 行触发面含界面/文档/运营/客户词与角色 11~14 分界",
          all(w in disp_text for w in ("界面设计", "角色 11", "角色 12", "角色 13", "角色 14", "运营活动", "客户成功")))

    # G9 存量卡回填（V1.8.10 03/04 首例 → V1.8.11 泛化为 00~10 十一卡全量深化循环）
    legacy_cards = [
        ("00-custody-research-expert", ["V2.0", "职责边界声明", "industry-policy-search", "信源"], [], None),
        ("01-business-analyst", ["V2.0", "职责边界声明", "market-feasibility-check", "需求池"], [], None),
        ("02-product-manager", ["V2.0", "职责边界声明", "TPL-SRS", "prd-generation"], [], None),
        ("03-technical-architect", ["V2.0", "职责边界声明", "TPL-ARC", "scenario-architecture-checklists"],
         ["architecture-decision-records", "scenario-architecture-checklists"], None),
        ("04-fullstack-developer", ["V2.0", "职责边界声明", "TPL-DDD", "fullstack-implementation"],
         ["contract-testing"], "DBA"),
        ("05-security-expert", ["V2.0", "职责边界声明", "owasp-top10-audit", "STRIDE"], [], None),
        ("06-testing-expert", ["V2.0", "职责边界声明", "test-case-generation", "测试金字塔"], [], None),
        ("07-compliance-expert", ["V2.0", "职责边界声明", "regulatory-compliance-check", "DPIA"], [], None),
        ("08-data-algorithm-expert", ["V2.0", "职责边界声明", "data-metrics-design", "LLM 评估"], [], None),
        ("09-sre-expert", ["V2.0", "职责边界声明", "cicd-pipeline-config", "SLO"], [], "DBA"),
        ("10-project-manager", ["V2.0", "职责边界声明", "epic-task-breakdown", "关键路径"], [], None),
    ]
    for card, musts, new_skills, banned in legacy_cards:
        cpath = os.path.join(root, ".standards/roles", card + ".md")
        tcard = open(cpath, encoding="utf-8").read() if os.path.isfile(cpath) else ""
        miss = [m for m in musts if m not in tcard]
        check("G9", f"{card} V2.0 深化要素齐备（边界表+专属锚点）", not miss and bool(tcard),
              f"缺: {miss}" if miss else "")
        if banned:
            check("G9", f"{card} 无 {banned} 悬挂引用", banned not in tcard)
        for sk in new_skills:
            check("G9", f"{card} 绑定新技能 {sk} 存在且被卡引用",
                  sk in tcard and os.path.isfile(
                      os.path.join(root, ".standards/skills", sk + ".md")))
    skill_upgrades = [
        ("system-design-doc", ["六元组", "战术模式映射"]),
        ("fullstack-implementation", ["traceparent", "场景检查清单"]),
        ("prd-generation", ["TPL-SRS", "Given"]),
        ("owasp-top10-audit", ["提示注入", "红队"]),
        ("test-case-generation", ["评测集覆盖", "稳定性长跑"]),
        ("regulatory-compliance-check", ["lawful basis", "算法备案"]),
        ("data-metrics-design", ["PII 埋点红线", "一致性"]),
        ("cicd-pipeline-config", ["语义缓存", "冷启动"]),
        ("incident-response-sop", ["错误预算", "OPS"]),
        ("epic-task-breakdown", ["Scrum", "SAFe"]),
    ]
    for sk, anchors in skill_upgrades:
        sp = os.path.join(root, ".standards/skills", sk + ".md")
        st = open(sp, encoding="utf-8").read() if os.path.isfile(sp) else ""
        miss = [a for a in anchors if a not in st]
        check("G9", f"技能 {sk} 深化锚点齐备（{anchors[0]}）", not miss and bool(st),
              f"缺: {miss}" if miss else "")
    # 卡↔技能双向绑定（V1.8.12 实数派生：每卡引用技能均存在 + 每技能文件被 ≥1 卡引用）
    card_skill_refs = {}
    for rf in roles_actual:
        s = open(os.path.join(roles_dir, rf + ".md"), encoding="utf-8").read()
        card_skill_refs[rf] = set(re.findall(r"skills/([\w\-]+)\.md", s))
    all_card_refs = set().union(*card_skill_refs.values()) if card_skill_refs else set()
    missing_refs = sorted(all_card_refs - set(skills_actual))
    orphan_skill_files = sorted(set(skills_actual) - all_card_refs)
    check("G9", "卡↔技能双向绑定一致（引用可达+零孤儿技能文件）",
          not missing_refs and not orphan_skill_files,
          f"缺:{missing_refs[:3]} 孤儿:{orphan_skill_files[:3]}" if (missing_refs or orphan_skill_files) else "")
    check("G9", "SDLC 0.1 表含 03/04 深化新技能行（身份锚点，非计数）",
          "architecture-decision-records, scenario-architecture-checklists" in sdlc_text
          and "refactoring-guide, contract-testing" in sdlc_text)
    # （G9 记录存在断言已并入上方实数派生双向检查——V1.8.19 根治）
    check("G9", "调度器 SDLC 行触发面含架构决策/契约测试词",
          "架构决策" in disp_text and "契约测试" in disp_text)

    # G10 四套规范最小门禁（V1.8.13 · P0 收编：CR/CODING/UI/TPL——审计实证此前零机器断言）
    # —— CR 质量门
    cr_rules_p = os.path.join(root, ".standards", "code-review", "RULES.md")
    cr_rules = open(cr_rules_p, encoding="utf-8").read() if os.path.isfile(cr_rules_p) else ""
    check("G10", "CR: code-review/RULES.md 存在且含 P0/P1 分级体系",
          bool(cr_rules) and "P0" in cr_rules and "P1" in cr_rules)
    cr_json_p = os.path.join(root, ".standards", "code-review", "global-code-review-rules.json")
    cr_json_ok = False
    cr_json_detail = "文件缺失"
    if os.path.isfile(cr_json_p):
        try:
            json.load(open(cr_json_p, encoding="utf-8"))
            cr_json_ok = True
        except Exception as e:
            cr_json_detail = f"解析失败: {e}"
    check("G10", "CR: global-code-review-rules.json 存在且可 JSON 解析", cr_json_ok,
          "" if cr_json_ok else cr_json_detail)
    card04_g10 = open(os.path.join(roles_dir, "04-fullstack-developer.md"),
                      encoding="utf-8").read() if os.path.isfile(
        os.path.join(roles_dir, "04-fullstack-developer.md")) else ""
    check("G10", "CR: 04 卡引用 code-review/RULES.md（交付门引用闭环）",
          "code-review/RULES.md" in card04_g10)
    # —— CODING 编码约束
    coding_md_p = os.path.join(root, ".standards", "CODING.md")
    coding_md = open(coding_md_p, encoding="utf-8").read() if os.path.isfile(coding_md_p) else ""
    check("G10", "CODING: 入口 CODING.md 存在且含规范标识 CODING-STD-001",
          "CODING-STD-001" in coding_md)
    coding_rules_n = len(os.listdir(os.path.join(root, ".standards", "coding-rules", "rules"))) \
        if os.path.isdir(os.path.join(root, ".standards", "coding-rules", "rules")) else 0
    coding_cfg_n = len(os.listdir(os.path.join(root, ".standards", "coding-rules", "configs"))) \
        if os.path.isdir(os.path.join(root, ".standards", "coding-rules", "configs")) else 0
    check("G10", "CODING: coding-rules/rules 与 configs 非空（实数派生）",
          coding_rules_n >= 1 and coding_cfg_n >= 1,
          f"rules={coding_rules_n} configs={coding_cfg_n}")
    check("G10", "CODING: 调度器注册表含 CODING 行（路由闭环）", "CODING.md" in disp_text)
    # —— UI 设计
    ui_rules_p = os.path.join(root, ".standards", "ui-design", "UI-RULES.md")
    ui_rules = open(ui_rules_p, encoding="utf-8").read() if os.path.isfile(ui_rules_p) else ""
    check("G10", "UI: UI-RULES.md 存在且含规范标识 UI-STD-001", "UI-STD" in ui_rules)
    ui_tokens_p = os.path.join(root, ".standards", "ui-design", "design-tokens.json")
    ui_tokens_ok = False
    ui_tokens_detail = "文件缺失"
    if os.path.isfile(ui_tokens_p):
        try:
            json.load(open(ui_tokens_p, encoding="utf-8"))
            ui_tokens_ok = True
        except Exception as e:
            ui_tokens_detail = f"解析失败: {e}"
    check("G10", "UI: design-tokens.json 存在且可 JSON 解析", ui_tokens_ok,
          "" if ui_tokens_ok else ui_tokens_detail)
    card11_g10 = open(os.path.join(roles_dir, "11-interaction-ux-designer.md"),
                      encoding="utf-8").read() if os.path.isfile(
        os.path.join(roles_dir, "11-interaction-ux-designer.md")) else ""
    check("G10", "UI: 11 号卡引用 ui-design 资产（引用闭环）",
          ("ui-design" in card11_g10 or "UI-RULES" in card11_g10))
    # —— TPL 模板库
    tpl_dir = os.path.join(root, ".standards", "templates")
    tpl_files = sorted(f for f in os.listdir(tpl_dir) if f.startswith("TPL-") and f.endswith(".md")) \
        if os.path.isdir(tpl_dir) else []
    tpl_readme_p = os.path.join(tpl_dir, "README.md")
    tpl_readme = open(tpl_readme_p, encoding="utf-8").read() if os.path.isfile(tpl_readme_p) else ""
    check("G10", "TPL: README 存在且含库标识 STD-TPL-001 与标准依据锚",
          "STD-TPL-001" in tpl_readme and "29148" in tpl_readme)
    tpl_index = set(re.findall(r"(TPL-[A-Z]+)\.md", tpl_readme))
    tpl_actual = {f[:-3] for f in tpl_files}
    check("G10", "TPL: README 选型矩阵与模板文件双向一致（实数派生）",
          tpl_index == tpl_actual and bool(tpl_actual),
          f"索引-文件差集: {sorted(tpl_index ^ tpl_actual)}" if tpl_index != tpl_actual else "")
    tpl_head_bad = [f for f in tpl_files
                    if not open(os.path.join(tpl_dir, f), encoding="utf-8").readline().startswith("# 模板 TPL-")]
    check("G10", "TPL: 每张模板首行含 '# 模板 TPL-' 标题锚", not tpl_head_bad,
          f"缺锚: {tpl_head_bad}" if tpl_head_bad else "")

    # —— P1 收编锚点（V1.8.14：AETC 留存脱敏 / OPS 灾备量化 / incident V1.2 / UI a11y / TPL↔技能互指）
    aetc_md = open(os.path.join(root, ".standards", "AETC.md"), encoding="utf-8").read() \
        if os.path.isfile(os.path.join(root, ".standards", "AETC.md")) else ""
    check("G10", "AETC: 留存与脱敏策略锚点（retention_days/PII 掩码）",
          "留存策略" in aetc_md and "retention_days" in aetc_md and "脱敏" in aetc_md)
    ops_md = open(os.path.join(root, ".standards", "OPS.md"), encoding="utf-8").read() \
        if os.path.isfile(os.path.join(root, ".standards", "OPS.md")) else ""
    check("G10", "OPS: 灾备量化与度量归属锚点（RTO/RPO/DORA/混沌）",
          all(w in ops_md for w in ("RTO", "RPO", "DORA", "混沌")))
    irs = open(os.path.join(root, ".standards", "skills", "incident-response-sop.md"),
               encoding="utf-8").read() if os.path.isfile(
        os.path.join(root, ".standards", "skills", "incident-response-sop.md")) else ""
    check("G10", "incident-response-sop V1.2 锚点（RTO 对账/MTTR 产数/演练标记）",
          all(w in irs for w in ("RTO", "MTTR", "drill")))
    ap = open(os.path.join(root, ".standards", "ui-design", "ai-antipatterns-checklist.md"),
              encoding="utf-8").read() if os.path.isfile(
        os.path.join(root, ".standards", "ui-design", "ai-antipatterns-checklist.md")) else ""
    check("G10", "UI: a11y 五项判据锚点（WCAG 2.1 AA 最低集）",
          all(w in ap for w in ("WCAG", "CHK-26", "CHK-29", "键盘")))
    tpl_pairs = [
        ("system-design-doc", "TPL-ARC"), ("prd-generation", "TPL-SRS"),
        ("fullstack-implementation", "TPL-DDD"), ("api-contract-definition", "TPL-IDD"),
        ("api-doc-generation", "TPL-APIREF"), ("user-manual-authoring", "TPL-UM"),
        ("user-manual-authoring", "TPL-RN"),
    ]
    pair_missing = [f"{s}↔{t}" for s, t in tpl_pairs
                    if t not in (open(os.path.join(root, ".standards", "skills", s + ".md"),
                                      encoding="utf-8").read()
                                 if os.path.isfile(os.path.join(root, ".standards", "skills", s + ".md")) else "")]
    check("G10", "TPL↔技能成文协议互指（七对：ARC/SRS/DDD/IDD/APIREF/UM/RN）", not pair_missing,
          f"缺: {pair_missing}" if pair_missing else "")
    dvd = open(os.path.join(root, ".standards", "skills", "document-visual-design.md"),
               encoding="utf-8").read() if os.path.isfile(
        os.path.join(root, ".standards", "skills", "document-visual-design.md")) else ""
    check("G10", "document-visual-design 技能存在且含版式三查铁律",
          bool(dvd) and "@page" in dvd and "越界" in dvd and "空页" in dvd)
    card12 = open(os.path.join(roles_dir, "12-technical-writer.md"),
                  encoding="utf-8").read() if os.path.isfile(
        os.path.join(roles_dir, "12-technical-writer.md")) else ""
    sdlc_p2 = "Conway" in sdlc_text and "组织适配声明" in sdlc_text and "互斥对不可同人" in sdlc_text
    check("G10", "SDLC.md V1.8 含 Conway 组织适配声明（多人映射三原则）", sdlc_p2)
    cicd_t = open(os.path.join(root, ".standards", "skills", "cicd-pipeline-config.md"),
                  encoding="utf-8").read() if os.path.isfile(
        os.path.join(root, ".standards", "skills", "cicd-pipeline-config.md")) else ""
    check("G10", "cicd-pipeline-config V2.1 含 SBOM 交付要求（CycloneDX+R-11）",
          "SBOM" in cicd_t and "CycloneDX" in cicd_t and "R-11" in cicd_t)
    meta_t = open(os.path.join(root, ".standards", "META.md"), encoding="utf-8").read() \
        if os.path.isfile(os.path.join(root, ".standards", "META.md")) else ""
    check("G10", "META.md V1.1 含规范版本语义（SemVer-like+breaking 字段）",
          "版本语义" in meta_t and "breaking" in meta_t and "MAJOR" in meta_t)
    _pack_root = os.path.dirname(root) if os.path.basename(root) == ".standards" else root
    _pr = open(os.path.join(_pack_root, "README.md"), encoding="utf-8").read() \
        if os.path.isfile(os.path.join(_pack_root, "README.md")) else ""
    # V1.9.3 修复：豁免判定从"文件不存在"改为"文件身份"——用户项目自己的 README.md（无 STD-HUB 标识）不误伤
    check("G10", "版本控制策略声明在场（pack README；非 STD-HUB 包 README 环境跳过）",
          ("版本控制策略" in _pr) or ("STD-HUB-001" not in _pr))

    check("G10", "12 号卡 V2.0 绑定版式技能与交付文档族接线",
          "V2.0" in card12 and "document-visual-design" in card12
          and "TPL-UM" in card12 and "TPL-APIREF" in card12 and "TPL-RN" in card12)

    tcg = open(os.path.join(root, ".standards", "skills", "test-case-generation.md"),
               encoding="utf-8").read() if os.path.isfile(
        os.path.join(root, ".standards", "skills", "test-case-generation.md")) else ""
    check("G10", "test-case-generation V2.1 视觉回归四要素（基线/防抖/容差/CLS）",
          all(w in tcg for w in ("视觉回归", "防抖", "容差", "CLS", "基线")))
    ap2 = open(os.path.join(root, ".standards", "ui-design", "ai-antipatterns-checklist.md"),
               encoding="utf-8").read() if os.path.isfile(
        os.path.join(root, ".standards", "ui-design", "ai-antipatterns-checklist.md")) else ""
    check("G10", "ui-design 视觉容差判据（CHK-30/31 容差+还原度三级）",
          "CHK-30" in ap2 and "CHK-31" in ap2 and "容差" in ap2)

    # 汇总
    report_lines.insert(1, f"\n结果：{'PASS ✅' if errors == 0 else 'FAIL ❌'} | "
                            f"{len([l for l in report_lines if '[PASS]' in l])} 项通过 / {errors} 项失败\n")
    out = os.path.join(root, "suite-check-report.md")
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(report_lines) + "\n")
    print("\n".join(report_lines))
    print("\n报告已写入:", out)
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
