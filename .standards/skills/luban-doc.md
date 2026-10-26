---
skill_name: "luban_doc"
version: "1.0"
role: "技术写作与文档工程师"
phase: "五、质量与验证（文档交付节点）"
trigger_keywords: ["写报告", "编制方案", "出调研文档", "白皮书", "成稿", "PDF 报告", "文档工程", "报告全流程", "分章写作", "合并质检", "scaffold", "merge_qa", "落成正式文档"]
input_types: ["内容已定的文档素材（要点/章节规划）", "文档类型（报告/方案/白皮书/调研）", "输出目录"]
output_types: ["多页中文 PDF（封面+目录+正文+页码）", "cover.html 源码", "merge_qa 质检报告（页数/链接数/目标页分布）"]
bound_roles: ["12-技术写作与文档工程师"]
---

# 技能模块：luban_doc

> 鲁班文档工程手艺层——PDF Report 线全流程生产管线（判型记录：
> meta/records/form-classification-luban-doc-v1.0.yaml·技能形态·全局级·与
> document-visual-design 正交：12 号卡管视觉规格层，本技能管生产管线层）。

## 1. 技能描述

本技能承载多页中文交付文档（报告/方案/白皮书/调研）的 **PDF 工程化生产管线**：
脚手架一键起步、分章模块化写作、机制化目录页码、合并质检一步到位。
能力边界：**只做 Report 线**；海报/信息图走 pdf 技能 Creative 线、学术论文走
Academic 线、已有 PDF 加工走 Process 线（底层排版规范均由 pdf 技能承载——
依赖声明：三态探测定位 pdf 技能——环境变量 PDF_SKILL_DIR / 本技能同级 pdf/
目录 / 开发态回退）。md 仅作输入格式，产物恒为 PDF。

## 2. 触发条件

- 输入包含「写报告」「编制方案」「出调研文档」「白皮书」「成稿」「PDF 报告」等
- 12 号卡文档交付任务且篇幅预期 >5 页（多页中文）
- 内容已定、要落成正式文档的任务形态

## 3. 执行步骤（六步法·完整版见技能包 .standards/luban-doc/SKILL.md）

1. **判定与加载**：确认 Report 线（非海报/学术/加工——否则转 pdf 技能对应线）
2. **脚手架起步**：`python3 <技能根>/scripts/scaffold.py --title T --author A --dir D`
   → 生成四件套（build_report.py / cover.html / chapters_part1.py）
3. **封面定制+双重校验**：改文案后 poster_validate + cover_validate 全过才渲染
4. **分章写作**：章内容落盘 chapters_part*.py（长文不依赖对话上下文）
5. **构建+合并质检**：build_report.py 出 body.pdf → merge_qa.py 合并封面+校验
   TOC 链接（禁手写 pypdf 逐页 add_page——丢链接）
6. **终检交付**：pdf_qa error=0 + font.check total_issues=0 + 页码连续抽查 →
   send_file 双件套（PDF+cover.html）+精确数字汇报

## 4. 铁律（违反即返工）

1. 脚手架起步——机制代码不接受重写
2. 分章落盘——内容只在文件里
3. 封面双重校验不过不渲染
4. 合并必须走 merge_qa（保 TOC 链接）
5. 质检带数字——"error 0 / 字形缺失 0 / 链接 56 个有效"，不写"都过了"

## 5. 分发与依赖

- **P3 完整技能包**：`.standards/luban-doc/`（SKILL.md+scripts+assets+references+evals）
  整体分发（对齐 ucss:code-scan 先例）；**依赖连带**：`.standards/pdf-skill/` 同装
  （校验器族 poster_validate/pdf_qa/font.check 在该包——install.sh p3_install 4 对）
- 排障与改机制代码前必读：技能包 `references/pitfalls.md`（实战踩坑清单·断言化）

## 6. 与相关技能的关系

| 技能 | 分工 | 衔接点 |
|---|---|---|
| document-visual-design（12 号卡） | 视觉规格层：版式基线/QA 阈值 | 本技能管线调的校验器=其三查工具化 |
| pdf 技能（依赖） | 底层排版规范四线 | 本技能=Report 线之上的工程化打法层 |
