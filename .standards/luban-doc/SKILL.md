---
name: luban-doc
description: 鲁班文档工程手艺层——报告/方案/白皮书/调研文档的 PDF 全流程制作打法。当用户要求写报告、编制方案、出调研文档、成稿白皮书、技术方案文档、交付 PDF 报告，或任何"内容已定、要落成正式文档"的任务时，使用本技能。它提供脚手架一键起步、分章模块化写作、机制化目录页码、合并质检一步到位的完整工程链，底层排版规范仍由 pdf 技能承载。凡涉及多页中文 PDF 文档交付，先读本技能。
compatibility: 依赖 pdf 技能提供底层排版规范——按三态探测定位：①环境变量 PDF_SKILL_DIR ②本技能同级 pdf/ 目录（P3 平铺形态）③开发态 /home/z/my-project/skills/pdf；依赖系统字体 NotoSerifSC/SarasaMonoSC、reportlab、pypdf、pymupdf。
---

# luban-doc — 鲁班文档工程手艺层

本技能是 pdf 技能（Report 线）之上的**工程化打法层**：pdf 技能回答"排版规范是什么"，
本技能回答"一份 15 页中文报告从零到交付，哪几步、每步用什么命令、哪里会炸、炸了怎么判"。
所有机制代码来自真实项目实战（2026-10-17 REA 调研方案 15 页 A4 全链验证）。

## 能力边界

**做**：多页中文文档（报告/方案/白皮书/调研/分析）的 PDF 全流程——封面、目录、正文、
页眉页脚页码、合并、质检、交付。

**不做**（仍走 pdf 技能路由）：
- 海报/信息图/证书 → pdf 技能 Creative 线
- 学术论文/LaTeX → pdf 技能 Academic 线
- 已有 PDF 的加工（提取/合并表单）→ pdf 技能 Process 线
- 内容含 emoji → pdf 技能 Creative 线（ReportLab 渲染 emoji 为方块）

## 工作流（六步法）

### 第 0 步：判定与加载

1. 读 pdf 技能 SKILL.md 的路由表，确认 Report 线适用（结构化文档、无 emoji、A4）
2. 读 pdf 技能的 `briefs/report.md` 与 `configs/fonts.md`（排版规范的正本）
3. 长文档（预估 >10 轮工具调用）必须用 TodoWrite 立任务清单

### 第 1 步：脚手架起步

```bash
python3 <本技能根>/scripts/scaffold.py \
  --title "文档标题" --author "署名" --dir <输出目录>
```

生成四件套：
- `build_report.py` —— 主构建脚本（字体注册/Crystal Blue 调色板/组件库/TOC 机制/页眉页脚，含全部踩坑修复）
- `cover.html` —— 封面模板（占位符已替换为实际标题，布局经碰撞检测验证）
- `chapters_part1.py` —— 章节骨架（含组件用法示例）
- 后续命令提示（照抄执行）

**禁止**从零手写主构建脚本——机制代码里埋着四个静默炸点（见 pitfalls.md），
脚手架版本已全部修复。

### 第 2 步：封面定制与校验

1. 按文档气质改 `cover.html` 的 subtitle/summary/meta 行（标题主行已由脚手架写入）
2. 双重校验，全过才准渲染：

```bash
PDF_SKILL_DIR=<pdf 技能根·按三态探测：环境变量/同级 pdf//开发态回退>
python3 "$PDF_SKILL_DIR/scripts/poster_validate.py" check-html cover.html
node "$PDF_SKILL_DIR/scripts/cover_validate.js" cover.html   # 文字碰撞，必须 0 error
```

3. 渲染（宽高必须显式 794px 防 dpi 失配）：

```bash
node "$PDF_SKILL_DIR/scripts/html2poster.js" cover.html --output cover.pdf --width 794px
```

封面文案语言与用户提问语言一致；改布局时优先动 top/间距，别动锚点结构。

### 第 3 步：分章写作（防上下文丢失）

- 每章一个函数（`ch1` `ch2`...），放在 `chapters_part*.py`，用 `importlib` 动态发现
- 每函数 150-400 行为宜，写完立即落盘——**文件在，上下文压缩也不怕**
- 组件只从 build_report import：h1/h2/h3/para/bullet/callout/code_block/data_table/caption
- 内容深度铁律（pdf 技能反浅写规范）：每段≥3 句、每节≥150 字、列表必须带解释语境

### 第 4 步：构建正文

```bash
cd <输出目录> && python3 build_report.py
```

产物 `body.pdf`。构建脚本内置机制（勿改，原理见 pitfalls.md）：
- TOC 自动生成（可点击跳转）+ 单页收纳（tableStyle 已清零 padding）
- 页码从正文第一章起算（TOC 页不计），页眉页脚仅正文区绘制
- 章节函数返回值支持嵌套列表（自动展平）

### 第 5 步：合并与质检（一步到位）

```bash
python3 <本技能根>/scripts/merge_qa.py \
  --dir <输出目录> --title "文档标题" --author "署名"
```

自动完成：append 合并（保留 TOC 链接）→ 封面 scale 统一 A4 精确值（消 dpi 失配 error）
→ 写元数据 → 链接核验报告（数量 + 目标页分布）。

随后跑终检：

```bash
python3 "$PDF_SKILL_DIR/scripts/pdf_qa.py" --skip-cover <最终.pdf>
python3 "$PDF_SKILL_DIR/scripts/pdf.py" font.check <最终.pdf>
```

**验收门禁**（棘轮，只增不减）：
- pdf_qa error = 0（warning 里的"TOC not clickable"若与 merge_qa 链接报告矛盾，以实测为准）
- font.check total_issues = 0
- merge_qa 报告 TOC 链接数 > 0 且目标页落在正文范围
- 页脚页码连续（抽查首中尾三页）

### 第 6 步：交付

- 双件套：最终 PDF + cover.html 源码，放 `<输出目录>/交付物/`
- send_file 逐一发送，汇报：路径、页数、文件大小、质检结论（精确数字，不写"都过了"）

## 铁律（违反即返工）

1. **脚手架起步**——不从零写主构建脚本，机制代码不接受重写
2. **分章落盘**——长文档内容只存在于文件里，不依赖对话上下文
3. **封面双重校验**——poster_validate + cover_validate 全过才渲染
4. **合并走 merge_qa**——禁止手写 pypdf 逐页 add_page（丢链接）
5. **质检带数字**——验收结论精确到"error 0 / 字形缺失 0 / 链接 56 个有效"

## 资产清单

| 路径 | 用途 | 何时读 |
|---|---|---|
| `scripts/scaffold.py` | 脚手架 | 第 1 步执行 |
| `scripts/merge_qa.py` | 合并+质检 | 第 5 步执行 |
| `assets/build_report_template.py` | 主构建模板 | 不直接读（scaffold 消费） |
| `assets/cover_template.html` | 封面模板 | 不直接读（scaffold 消费） |
| `references/pitfalls.md` | 实战踩坑清单（断言化） | 改机制代码前必读；排障时读 |
