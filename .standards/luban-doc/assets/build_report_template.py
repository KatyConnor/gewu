# -*- coding: utf-8 -*-
"""__TITLE__ — 主构建脚本（luban-doc 脚手架生成）

机制代码含实战修复，勿随意改动：
- TOC 可点击跳转 + 单页收纳（tableStyle 清零 padding）
- 页码从正文第一章起算，页眉页脚仅正文区绘制（body_start 跨轮保留）
- 章节函数返回值支持嵌套列表（自动展平）
改动前读 skills/luban-doc/references/pitfalls.md
"""
import glob
import importlib
import os
import re

from reportlab.lib.pagesizes import A4
from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT, TA_CENTER, TA_JUSTIFY
from reportlab.lib.styles import ParagraphStyle
from reportlab.platypus import (BaseDocTemplate, PageTemplate, Frame,
                                Paragraph, Spacer, Table, TableStyle,
                                Preformatted, CondPageBreak, PageBreak)
from reportlab.platypus.tableofcontents import TableOfContents

# ---------------------------------------------------------------- 字体注册
FONT_DIR_CN = "/usr/share/fonts/truetype/chinese"
FONT_DIR_SERIF = "/usr/share/fonts/truetype/noto-serif-sc"
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

pdfmetrics.registerFont(TTFont("NotoSerifSC",
    os.path.join(FONT_DIR_SERIF, "NotoSerifSC-Regular.ttf")))
pdfmetrics.registerFont(TTFont("NotoSerifSC-Bold",
    os.path.join(FONT_DIR_SERIF, "NotoSerifSC-Bold.ttf")))
pdfmetrics.registerFont(TTFont("NotoSerifSC-Black",
    os.path.join(FONT_DIR_SERIF, "NotoSerifSC-Black.ttf")))
pdfmetrics.registerFont(TTFont("SarasaMonoSC",
    os.path.join(FONT_DIR_CN, "SarasaMonoSC-Regular.ttf")))
pdfmetrics.registerFontFamily("NotoSerifSC", normal="NotoSerifSC",
                              bold="NotoSerifSC-Bold",
                              italic="NotoSerifSC", boldItalic="NotoSerifSC-Bold")

# ---------------------------------------------------------------- 调色板（Template 07 Crystal Blue）
PAGE_BG = colors.HexColor("#f5f9fc")      # 页面底
SECTION_BG = colors.HexColor("#e8f1f8")   # callout 底
ACCENT = colors.HexColor("#2d7ab3")       # 强调蓝
HEADER_FILL = colors.HexColor("#0a3d62")  # 表头深蓝
TEXT_PRIMARY = colors.HexColor("#1a2b3c")
TEXT_MUTED = colors.HexColor("#5a7184")

DOC_TITLE = "__TITLE__"
DOC_AUTHOR = "__AUTHOR__"
BASE = os.path.dirname(os.path.abspath(__file__))

PAGE_W, PAGE_H = A4
LM = RM = 0.83 * 72
TM, BM = 0.83 * 72, 0.83 * 72
AVAIL_W = PAGE_W - LM - RM

# ---------------------------------------------------------------- 样式
S = {}
S["h1"] = ParagraphStyle("h1", fontName="NotoSerifSC-Black", fontSize=17,
    leading=26, textColor=TEXT_PRIMARY, spaceBefore=18, spaceAfter=4,
    wordWrap="CJK")
S["h2"] = ParagraphStyle("h2", fontName="NotoSerifSC-Bold", fontSize=12.5,
    leading=20, textColor=HEADER_FILL, spaceBefore=16, spaceAfter=8,
    wordWrap="CJK")
S["h3"] = ParagraphStyle("h3", fontName="NotoSerifSC-Bold", fontSize=10.8,
    leading=17, textColor=TEXT_PRIMARY, spaceBefore=12, spaceAfter=6,
    wordWrap="CJK")
S["body"] = ParagraphStyle("body", fontName="NotoSerifSC", fontSize=10.5,
    leading=17.5, textColor=TEXT_PRIMARY, alignment=TA_JUSTIFY,
    spaceBefore=4, spaceAfter=8, wordWrap="CJK")
S["bullet"] = ParagraphStyle("bullet", parent=S["body"], leftIndent=14,
    bulletIndent=2, spaceAfter=5, bulletFontName="NotoSerifSC")
S["callout"] = ParagraphStyle("callout", fontName="NotoSerifSC",
    fontSize=9.8, leading=16, textColor=TEXT_PRIMARY, wordWrap="CJK")
S["code"] = ParagraphStyle("code", fontName="SarasaMonoSC", fontSize=8,
    leading=11.5, textColor=TEXT_PRIMARY)
S["caption"] = ParagraphStyle("caption", fontName="NotoSerifSC", fontSize=8.5,
    leading=13, textColor=TEXT_MUTED, alignment=TA_CENTER, spaceBefore=5,
    spaceAfter=12)
S["td"] = ParagraphStyle("td", fontName="NotoSerifSC", fontSize=9.2,
    leading=13.5, textColor=TEXT_PRIMARY, wordWrap="CJK")
S["td_s"] = ParagraphStyle("td_s", parent=S["td"], fontSize=8.6, leading=12.2)
S["toc_title"] = ParagraphStyle("toc_title", fontName="NotoSerifSC-Black",
    fontSize=18, leading=26, textColor=TEXT_PRIMARY, spaceAfter=14)
S["toc_l0"] = ParagraphStyle("toc_l0", fontName="NotoSerifSC-Bold",
    fontSize=10.5, leading=14, spaceBefore=8, textColor=TEXT_PRIMARY)
S["toc_l1"] = ParagraphStyle("toc_l1", fontName="NotoSerifSC",
    fontSize=9.2, leading=12.5, spaceBefore=2, textColor=TEXT_MUTED,
    leftIndent=18)


# ---------------------------------------------------------------- 组件
def h1(text):
    # 机制注记：h1 平铺不包 KeepTogether——容器上的 _toc 属性
    # afterFlowable 看不见，TOC 条目会静默丢失（pitfalls.md #1）
    flow = []
    flow.append(CondPageBreak(110))
    p = Paragraph(text, S["h1"])
    p._toc = (0, text)
    flow.append(p)
    rule = Table([[""]], colWidths=[AVAIL_W], rowHeights=[2.2])
    rule.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), ACCENT),
        ("FONTNAME", (0, 0), (-1, -1), "NotoSerifSC"),
        ("LEFTPADDING", (0, 0), (-1, -1), 0),
        ("RIGHTPADDING", (0, 0), (-1, -1), 0),
        ("TOPPADDING", (0, 0), (-1, -1), 0),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 0),
    ]))
    flow.append(rule)
    flow.append(Spacer(1, 12))
    return flow


def h2(text):
    p = Paragraph(text, S["h2"])
    p._toc = (1, text)
    return [CondPageBreak(70), p]


def h3(text):
    return [CondPageBreak(55), Paragraph(text, S["h3"])]


def para(text):
    return [Paragraph(text, S["body"])]


def bullet(items):
    out = []
    for it in items:
        out.append(Paragraph(it, S["bullet"], bulletText="•"))
    out.append(Spacer(1, 4))
    return out


def callout(text):
    inner = Paragraph(text, S["callout"])
    t = Table([[inner]], colWidths=[AVAIL_W - 8], hAlign="CENTER")
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), SECTION_BG),
        ("LINEBEFORE", (0, 0), (0, -1), 3, ACCENT),
        ("LEFTPADDING", (0, 0), (-1, -1), 12),
        ("RIGHTPADDING", (0, 0), (-1, -1), 12),
        ("TOPPADDING", (0, 0), (-1, -1), 9),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 9),
    ]))
    return [Spacer(1, 4), t, Spacer(1, 10)]


def code_block(text, label=None):
    pre = Preformatted(text, S["code"])
    t = Table([[pre]], colWidths=[AVAIL_W - 8], hAlign="CENTER")
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#f0f4f8")),
        ("LINEBEFORE", (0, 0), (0, -1), 2.5, ACCENT),
        ("BOX", (0, 0), (-1, -1), 0.4, colors.HexColor("#d0dde8")),
        ("LEFTPADDING", (0, 0), (-1, -1), 10),
        ("RIGHTPADDING", (0, 0), (-1, -1), 10),
        ("TOPPADDING", (0, 0), (-1, -1), 8),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 8),
    ]))
    if label:
        cap = Paragraph(label, S["caption"])
        return [Spacer(1, 6), t, cap]
    return [Spacer(1, 6), t, Spacer(1, 8)]


def data_table(header, rows, widths, small=False, align_cols=None):
    """通用数据表。widths 为比例（和为 1）；单元格一律 Paragraph 包裹（CJK 铁律）"""
    style = S["td_s"] if small else S["td"]
    hstyle = ParagraphStyle("th", parent=style, fontName="NotoSerifSC-Bold",
                            textColor=colors.white, fontSize=style.fontSize + 0.2)
    align_cols = align_cols or []

    def cell(r, c, v):
        al = TA_CENTER if c in align_cols else TA_LEFT
        st = ParagraphStyle("c%d%d" % (r, c), parent=style, alignment=al)
        return Paragraph(str(v), st)

    data = [[Paragraph(h, hstyle) for h in header]]
    for r, row in enumerate(rows):
        data.append([cell(r, c, v) for c, v in enumerate(row)])
    col_ws = [AVAIL_W * w for w in widths]
    assert abs(sum(widths) - 1.0) < 0.01, "widths 比例和须为 1"
    t = Table(data, colWidths=col_ws, hAlign="CENTER", repeatRows=1)
    cmds = [
        ("BACKGROUND", (0, 0), (-1, 0), HEADER_FILL),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("BOX", (0, 0), (-1, -1), 0.7, HEADER_FILL),
        ("INNERGRID", (0, 0), (-1, -1), 0.35, colors.HexColor("#c5d5e3")),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
    ]
    for i in range(1, len(data)):
        if i % 2 == 0:
            cmds.append(("BACKGROUND", (0, i), (-1, i),
                         colors.HexColor("#eef4f9")))
    t.setStyle(TableStyle(cmds))
    return [Spacer(1, 8), t, Spacer(1, 4)]


def caption(text):
    return [Paragraph(text, S["caption"])]


# ---------------------------------------------------------------- TOC 模板
class TocDocTemplate(BaseDocTemplate):
    """机制注记（勿改，原理见 pitfalls.md #2/#3）：
    - body_start 跨 multiBuild 轮保留收敛值：onPage 在页面开头执行，
      早于本轮 afterFlowable，正文首页页眉依赖上一轮的值
    - _body_start_set 每轮 handle_documentBegin 重置：仅文档顺序第一个
      h1 决定正文起始页，后续章不得重置（否则页码每章归零）
    """

    def afterInit(self):
        self.body_start = None
        self._body_start_set = False

    def handle_documentBegin(self):
        self._body_start_set = False
        BaseDocTemplate.handle_documentBegin(self)

    def afterFlowable(self, flowable):
        toc = getattr(flowable, "_toc", None)
        if toc:
            level, text = toc
            if level == 0 and not self._body_start_set:
                self.body_start = self.page
                self._body_start_set = True
            key = "toc-%d-%s" % (level, re.sub(r"\W+", "", text)[:24])
            self.canv.bookmarkPage(key)
            display = self.page - (self.body_start or self.page) + 1
            self.notify("TOCEntry", (level, text, display, key))


def on_page(canvas, doc):
    canvas.saveState()
    canvas.setFillColor(PAGE_BG)
    canvas.rect(0, 0, PAGE_W, PAGE_H, stroke=0, fill=1)
    body_start = getattr(doc, "body_start", None)
    if body_start is not None and canvas.getPageNumber() >= body_start:
        canvas.setFont("NotoSerifSC", 7.5)
        canvas.setFillColor(TEXT_MUTED)
        canvas.drawString(LM, PAGE_H - 0.55 * 72, DOC_TITLE)
        canvas.setStrokeColor(ACCENT)
        canvas.setLineWidth(0.8)
        canvas.line(LM, PAGE_H - 0.63 * 72, PAGE_W - RM, PAGE_H - 0.63 * 72)
        canvas.setFont("NotoSerifSC", 7.5)
        canvas.setFillColor(TEXT_MUTED)
        canvas.drawString(LM, 0.5 * 72, DOC_AUTHOR)
        canvas.setFont("SarasaMonoSC", 8.5)
        canvas.drawRightString(PAGE_W - RM, 0.5 * 72,
                               str(canvas.getPageNumber() - body_start + 1))
    canvas.restoreState()


# ---------------------------------------------------------------- 章节发现
def discover_chapters():
    """按序发现 chapters_part*.py 中的 ch1..chN / appendix 函数。
    章节函数返回 flowable 列表（支持嵌套，构建时自动展平）。"""
    found = []
    for mod_path in sorted(glob.glob(os.path.join(BASE, "chapters_part*.py"))):
        mod_name = os.path.basename(mod_path)[:-3]
        mod = importlib.import_module(mod_name)
        for name in dir(mod):
            m = re.fullmatch(r"ch(\d+)", name)
            if m:
                found.append((int(m.group(1)), name, getattr(mod, name)))
            elif name == "appendix":
                found.append((999, name, getattr(mod, name)))
    found.sort(key=lambda x: x[0])
    return [(name, fn) for _, name, fn in found]


def build():
    out = os.path.join(BASE, "body.pdf")
    doc = TocDocTemplate(out, pagesize=A4,
                         leftMargin=LM, rightMargin=RM,
                         topMargin=TM, bottomMargin=BM,
                         title=DOC_TITLE, author=DOC_AUTHOR,
                         subject=DOC_TITLE)
    frame = Frame(LM, BM, AVAIL_W, PAGE_H - TM - BM, id="main")
    doc.addPageTemplates([PageTemplate(id="page", frames=[frame],
                                       onPage=on_page)])
    story = []
    # ---- 目录页
    story.append(Paragraph("目　录", S["toc_title"]))
    toc = TableOfContents()
    toc.levelStyles = [S["toc_l0"], S["toc_l1"]]
    toc.dotsMinLevel = 0
    # 机制注记：清零默认 6pt/条 内边距，30+ 条目录收进一页（pitfalls.md #4）
    toc.tableStyle = TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 0),
        ("RIGHTPADDING", (0, 0), (-1, -1), 0),
        ("TOPPADDING", (0, 0), (-1, -1), 0),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 0),
    ])
    story.append(toc)
    story.append(Spacer(1, 12))
    story.append(Paragraph(
        "本文档由 luban-doc 脚手架生成框架编制。内容与数据以正文为准；"
        "引用外部资料时请以原始出处复核。", S["callout"]))
    story.append(PageBreak())

    # ---- 正文（展平嵌套列表）
    def flatten(items):
        for it in items:
            if isinstance(it, list):
                yield from flatten(it)
            else:
                yield it

    chapters = discover_chapters()
    if not chapters:
        raise SystemExit("未发现章节函数：请创建 chapters_part1.py，"
                         "内含 ch1() 等函数返回 flowable 列表")
    print("章节序列:", [n for n, _ in chapters])
    for name, fn in chapters:
        story.extend(flatten(fn()))

    doc.multiBuild(story)
    print("body.pdf pages:", doc.page)
    return out


if __name__ == "__main__":
    build()
