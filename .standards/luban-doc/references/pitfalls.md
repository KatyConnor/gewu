# pitfalls.md — 实战踩坑清单（断言化）

来源：2026-10-17 REA 调研方案 15 页 A4 全链实战（首次全链验证通过）。
每条坑已固化为脚手架机制或质检断言。**改机制代码前必读本文件。**

---

## #1 KeepTogether 容器挂 `_toc` → TOC 条目静默丢失

**现象**：目录页条目缺失，或全部章节不进目录；无任何报错。

**根因**：`afterFlowable(flowable)` 只对 story 顶层元素调用。把 `_toc`
属性挂在 `KeepTogether([...])` 容器上，容器内部的 Paragraph 永远不会
作为顶层 flowable 经过 afterFlowable——通知从未发出。

**断言**：h1 的 Paragraph 必须平铺在 story 顶层（模板已实现：
`CondPageBreak + Paragraph + rule Table` 三件平铺）。
需要防孤行时用 `CondPageBreak(110)`，不用 KeepTogether 包标题。

**验证**：构建日志打印章节序列后，检查 body.pdf 目录页条目数 = 章节数 + 小节数。

---

## #2 multiBuild 轮间状态：`body_start` 保留、`_body_start_set` 重置

**现象**：两种相反的翻车——
a) 正文第一页没有页眉页脚（onPage 执行时 body_start 还是 None）；
b) 页码从每章重新起算（p7 出现"2"，应为"6"）。

**根因**：onPage 在页面**开头**执行，早于本轮 afterFlowable；multiBuild
多轮构建中需要上一轮的收敛值。而"第一章已定位"标志若跨轮保留，第二轮
会在错误的章节重设起点——若每轮不重置但每章都重设 body_start，页码归零。

**断言**（模板 TocDocTemplate 已实现）：
- `body_start` 在 `afterInit` 初始化，**跨轮保留**
- `_body_start_set` 在 `handle_documentBegin` 每轮**重置**
- 仅文档顺序第一个 level-0 条目设定 `body_start`

**验证**：pymupdf 提取每页右下角页码（y>780, x>450），应从正文首页
连续递增至末页；TOC 页与封面无页码。

---

## #3 TOC 单页收纳：tableStyle 必须清零 padding

**现象**：33 条目录溢出一行到下页，溢出行把"正文起始页"判定整体推后，
页码基准错位（连锁触发 #2 的假象）。

**根因**：TableOfContents 默认每条目上下各 6pt 单元格内边距，30 条以上
累积近 200pt。压缩 levelStyles 行距不够，必须清零 tableStyle padding。

**断言**（模板已实现）：
```python
toc.tableStyle = TableStyle([
    ("LEFTPADDING", (0,0), (-1,-1), 0), ("RIGHTPADDING", (0,0), (-1,-1), 0),
    ("TOPPADDING", (0,0), (-1,-1), 0), ("BOTTOMPADDING", (0,0), (-1,-1), 0),
])
```

**验证**：目录（含页尾说明段）全部落在 body.pdf 第 1 页。

---

## #4 封面 dpi 失配：渲染必须显式 `--width 794px`

**现象**：pdf_qa 报 page-size error：封面 540×842.9pt vs 正文 595.3×841.9pt。

**根因**：html2poster.js 默认视口 720px（=540pt @72dpi）；794px 才对应
A4@96dpi。不传 --width 时封面小 10%。

**断言**：封面渲染命令固定为
`node html2poster.js cover.html --output cover.pdf --width 794px`。
merge_qa.py 兜底：封面 scale 到 A4 精确值（595.276×841.89pt）。

**验证**：合并产物页面尺寸集合只有 1 个元素。

---

## #5 pypdf 逐页 `add_page` 丢 TOC 链接

**现象**：合并后目录不可点击；pymupdf `resolve_names()` 返回空。
pdf_qa 报 "TOC not clickable"。

**根因**：ReportLab 生成的 TOC 链接是**显式 /Dest**（指向页对象
IndirectObject），不是命名目标。逐页 `add_page` 复制注释但不重映射
Dest 里的页引用 → 链接悬空。`writer.append()` 才做目标重映射。

**断言**：合并一律走 `PdfWriter().append(file)`（merge_qa.py 已实现），
禁止逐页 add_page 拼装。

**验证**：merge_qa 输出的链接核验段：链接数 > 0 且目标页全部落在
正文范围（第 3 页起）。注意 pdf_qa 对显式 /Dest 形式有误报
（"ZERO clickable links"），以 merge_qa 的对象级实测为准。

---

## #6 长任务中断防线：分章落盘 + 验证命令合并跑

**现象**：单次会话 100 轮工具上限被长链路耗尽，收尾中断。

**根因**：内容全在对话上下文里未落盘；验证命令逐条跑烧轮次。

**断言**：
- 每章写完立即 Write 落盘（chapters_part*.py），上下文压缩/中断零损失
- 独立验证合并成单条命令跑（如 merge_qa 一步出四项核验）
- 超过 10 轮的任务先 TodoWrite 立清单，完成态实时更新

---

## 附：非断言类经验

- 封面文字碰撞：`cover_validate.js` 报的 label/value "overlap" 多为
  flex 行内基线误判——改单行文本流（`<span>` 顺序排列）可消
- `literal_eval` 相关：TOC 的 key 含逗号会被转义，key 生成避免用
  标点（模板用 `re.sub(r"\W+", "", text)[:24]`）
- 页眉字体：页脚页码用 SarasaMonoSC（等宽数字不跳动），页眉用
  NotoSerifSC 7.5pt + 分隔线 0.8pt ACCENT
- 质检 "Punctuation rules" warning（行首标点）为 ReportLab 断行
  边界情况，CJK wordWrap 下难全消，属可接受项
