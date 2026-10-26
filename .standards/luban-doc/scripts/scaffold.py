#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""luban-doc 脚手架 —— 一键生成文档工程四件套

用法：
  python3 scaffold.py --title "报告标题" --author "署名" --dir /path/to/outdir
                      [--subtitle "副标题"] [--summary "封面摘要"]
                      [--kicker "眉题"] [--date "2026-10-18"]

生成：
  outdir/build_report.py    主构建脚本（机制代码，勿改）
  outdir/cover.html         封面（改文案即可，布局已过碰撞校验）
  outdir/chapters_part1.py  章节骨架（在此写作）
"""
import argparse
import os
import shutil
import sys

SKILL_ASSETS = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                             "..", "assets"))
SKILL_ROOT = os.path.dirname(SKILL_ASSETS)  # 技能根（含 scripts/assets/references）


def find_pdf_skill():
    """pdf 技能目录三态探测（可移植——P3 安装到任意技能目录后不断链）：
    ① 环境变量 PDF_SKILL_DIR（显式指定最优先）
    ② 本技能同级 pdf/ 目录（P3 平铺形态：skills/luban-doc + skills/pdf）
    ③ 开发态回退 /home/z/my-project/skills/pdf
    返回 None 时调用方应提示用户设置 PDF_SKILL_DIR。"""
    for cand in (os.environ.get("PDF_SKILL_DIR", ""),
                 os.path.join(os.path.dirname(SKILL_ROOT), "pdf"),
                 "/home/z/my-project/skills/pdf"):
        if cand and os.path.isfile(os.path.join(cand, "scripts", "pdf_qa.py")):
            return os.path.abspath(cand)
    return None


PDF_SKILL = find_pdf_skill()


def main():
    ap = argparse.ArgumentParser(description="luban-doc scaffold")
    ap.add_argument("--title", required=True)
    ap.add_argument("--author", default="鲁班")
    ap.add_argument("--dir", required=True)
    ap.add_argument("--subtitle", default="")
    ap.add_argument("--summary", default="")
    ap.add_argument("--kicker", default="技术文档")
    ap.add_argument("--date", default="")
    ap.add_argument("--org", default="")
    args = ap.parse_args()

    outdir = os.path.abspath(args.dir)
    os.makedirs(outdir, exist_ok=True)

    if not args.date:
        import datetime
        args.date = datetime.date.today().strftime("%Y 年 %m 月 %d 日")
    if not args.org:
        args.org = args.author
    if not args.subtitle:
        args.subtitle = args.title
    if not args.summary:
        args.summary = ("（在此填入 2-3 句封面摘要：文档解决什么问题、"
                        "覆盖哪些内容、给谁看。）")

    title_html = args.title.replace("\n", "<br>")

    # ---- build_report.py
    with open(os.path.join(SKILL_ASSETS, "build_report_template.py"),
              encoding="utf-8") as f:
        t = f.read()
    t = t.replace("__TITLE__", args.title).replace("__AUTHOR__", args.author)
    with open(os.path.join(outdir, "build_report.py"), "w",
              encoding="utf-8") as f:
        f.write(t)

    # ---- cover.html
    with open(os.path.join(SKILL_ASSETS, "cover_template.html"),
              encoding="utf-8") as f:
        c = f.read()
    for k, v in {
        "__KICKER__": args.kicker,
        "__TITLE__": title_html,
        "__SUBTITLE__": args.subtitle,
        "__SUMMARY__": args.summary,
        "__META_L1__": "编制", "__META_V1__": args.author,
        "__META_L2__": "日期", "__META_V2__": args.date,
        "__ORG__": args.org,
        "__DATE__": args.date,
    }.items():
        c = c.replace(k, v)
    with open(os.path.join(outdir, "cover.html"), "w", encoding="utf-8") as f:
        f.write(c)

    # ---- chapters_part1.py
    shutil.copy(os.path.join(SKILL_ASSETS, "chapters_template.py"),
                os.path.join(outdir, "chapters_part1.py"))
    with open(os.path.join(outdir, "chapters_part1.py"), encoding="utf-8") as f:
        ch = f.read()
    ch = ch.replace("__TITLE__", args.title)
    with open(os.path.join(outdir, "chapters_part1.py"), "w",
              encoding="utf-8") as f:
        f.write(ch)

    pdf_skill = PDF_SKILL or "$PDF_SKILL_DIR"  # 探测失败时输出变量占位（用户显式设置）
    pdf_note = "" if PDF_SKILL else "（注意：未探测到 pdf 技能——先 export PDF_SKILL_DIR=<pdf 技能根>）\n  "
    print(f"""[luban-doc] 脚手架完成 → {outdir}

已生成：
  build_report.py    主构建脚本（机制代码，勿改；原理见技能 references/pitfalls.md）
  cover.html         封面（改 subtitle/summary/meta 文案；布局勿大动）
  chapters_part1.py  章节骨架（在此写作，可扩展 chapters_part2.py ...）

后续步骤（按序执行）：
  {pdf_note}1. 定制 cover.html 后校验+渲染：
     PDF_SKILL_DIR={pdf_skill}
     python3 "$PDF_SKILL_DIR/scripts/poster_validate.py" check-html {outdir}/cover.html
     node "$PDF_SKILL_DIR/scripts/cover_validate.js" {outdir}/cover.html
     node "$PDF_SKILL_DIR/scripts/html2poster.js" {outdir}/cover.html --output {outdir}/cover.pdf --width 794px
  2. 写作章节后构建正文：
     cd {outdir} && python3 build_report.py
  3. 合并+质检（一步到位）：
     python3 {os.path.join(SKILL_ROOT, "scripts", "merge_qa.py")} --dir {outdir} --title "{args.title}" --author "{args.author}"
  4. 终检：
     python3 "$PDF_SKILL_DIR/scripts/pdf_qa.py" --skip-cover {outdir}/<最终文件名>.pdf
     python3 "$PDF_SKILL_DIR/scripts/pdf.py" font.check {outdir}/<最终文件名>.pdf
""")


if __name__ == "__main__":
    sys.exit(main())
