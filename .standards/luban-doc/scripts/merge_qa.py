#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""luban-doc 合并质检 —— cover.pdf + body.pdf → 最终交付 PDF（一步到位）

动作：
  1. pypdf append 合并（保留 TOC 显式 /Dest 链接——逐页 add_page 会丢，pitfalls.md #5）
  2. 封面 scale 统一到正文 A4 精确值（消 72/96 dpi 失配 page-size error）
  3. 写元数据（Title/Author/Creator/Subject）
  4. 链接核验：TOC 页链接数 + 目标页分布（落在正文范围为有效）

用法：
  python3 merge_qa.py --dir <输出目录> --title "标题" --author "署名" [--name 文件名]
"""
import argparse
import os
import sys

from pypdf import PdfReader, PdfWriter

A4_W, A4_H = 595.276, 841.89


def main():
    ap = argparse.ArgumentParser(description="luban-doc merge & QA")
    ap.add_argument("--dir", required=True)
    ap.add_argument("--title", required=True)
    ap.add_argument("--author", default="鲁班")
    ap.add_argument("--name", default="")
    args = ap.parse_args()

    d = os.path.abspath(args.dir)
    cover = os.path.join(d, "cover.pdf")
    body = os.path.join(d, "body.pdf")
    for p in (cover, body):
        if not os.path.exists(p):
            sys.exit(f"[merge_qa] 缺文件: {p}")

    name = args.name or (args.title + ".pdf")
    out = os.path.join(d, name)

    w = PdfWriter()
    w.append(cover)
    w.append(body)

    # 封面统一到正文精确 A4（html2poster 产物 595.9pt vs ReportLab 595.3pt）
    page0 = w.pages[0]
    sx = A4_W / float(page0.mediabox.width)
    sy = A4_H / float(page0.mediabox.height)
    if abs(sx - 1) > 0.001 or abs(sy - 1) > 0.001:
        page0.scale(sx, sy)
        print(f"[merge_qa] 封面已缩放 {sx:.4f}x{sy:.4f} → A4 精确值")

    w.add_metadata({
        "/Title": args.title,
        "/Author": args.author,
        "/Creator": "Z.ai",
        "/Subject": args.title,
    })
    with open(out, "wb") as f:
        w.write(f)

    # ---- 核验
    r = PdfReader(out)
    sizes = {(round(float(p.mediabox.width), 1),
              round(float(p.mediabox.height), 1)) for p in r.pages}
    n_pages = len(r.pages)

    link_total, targets = 0, {}
    if n_pages >= 2:
        # TOC = 第 2 页（第 1 页封面）；链接 /Dest 指向页对象
        toc_page = r.pages[1]
        annots = toc_page.get("/Annots") or []
        page_ids = {p.indirect_reference.idnum: i + 1
                    for i, p in enumerate(r.pages)}
        for a in annots:
            obj = a.get_object()
            dest = obj.get("/Dest")
            if dest:
                link_total += 1
                tgt = page_ids.get(dest[0].idnum, "?")
                targets[tgt] = targets.get(tgt, 0) + 1

    print(f"[merge_qa] 输出: {out}")
    print(f"[merge_qa] 页数: {n_pages} | 页面尺寸集合: {sizes}")
    if len(sizes) > 1:
        print("[merge_qa] ⚠ 尺寸不一致——检查封面渲染 --width 794px 是否生效")
    print(f"[merge_qa] TOC 链接: {link_total} 个 | 目标页分布: "
          f"{dict(sorted(targets.items(), key=lambda x: str(x[0])))}")
    body_pages = set(range(2, n_pages + 1))
    in_range = sum(v for k, v in targets.items() if k in body_pages)
    if link_total == 0:
        print("[merge_qa] ❌ TOC 无链接——检查 body.pdf 是否 multiBuild 产物")
        sys.exit(1)
    if in_range < link_total:
        print(f"[merge_qa] ⚠ {link_total - in_range} 个链接目标不在正文范围（2..{n_pages}）")
    else:
        print(f"[merge_qa] ✅ 全部 {link_total} 链接目标落在正文范围")
    pdf_skill = None
    for cand in (os.environ.get("PDF_SKILL_DIR", ""),
                 os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "..", "pdf"),
                 os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "pdf"),
                 "/home/z/my-project/skills/pdf"):
        if cand and os.path.isfile(os.path.join(cand, "scripts", "pdf_qa.py")):
            pdf_skill = os.path.abspath(cand)
            break
    pdf_skill = pdf_skill or "$PDF_SKILL_DIR"
    print(f"""[merge_qa] 建议终检：
  python3 {pdf_skill}/scripts/pdf_qa.py --skip-cover "{out}"
  python3 {pdf_skill}/scripts/pdf.py font.check "{out}"
  （pdf_qa 的 "TOC not clickable" warning 若与本报告矛盾，以本报告实测为准）""")


if __name__ == "__main__":
    main()
