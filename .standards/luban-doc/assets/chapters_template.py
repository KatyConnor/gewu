# -*- coding: utf-8 -*-
"""章节骨架 —— 在此文件及后续 chapters_part*.py 中写作。

规则：
- 每章一个函数：ch1 / ch2 / ... / appendix（函数名决定装订顺序）
- 组件从 build_report import，返回 flowable 列表（支持嵌套）
- 每函数落盘一次；段落 ≥3 句、每节 ≥150 字（反浅写铁律）
"""
from build_report import (h1, h2, h3, para, bullet, callout, code_block,
                          data_table, caption)


def ch1():
    s = []
    s.append(h1("第一章　__TITLE__"))
    s.append(para(
        "在此写入本章导语。每段至少三句：先给出本章要解决什么问题，"
        "再交代结论或路径，最后说明材料组织方式，让读者带着结构感进入正文。"
        "段落深度是文档质量的第一道门禁——单句段落只允许出现在过渡语句中。"))
    s.append(h2("1.1　小节标题"))
    s.append(para("正文段落。数据要给精确数字与出处年份，论断要有支撑，"
                  "技术术语首次出现时给一句话解释。"))
    s.append(bullet([
        "列表项必须带解释语境，不允许光秃秃的关键词（反浅写铁律）",
        "每项一到两句，说明是什么、为什么重要或怎么用",
    ]))
    s.append(callout("重要提示或红线约束写在这里，每章至多一个。"))
    s.append(h2("1.2　表格示例"))
    s.append(data_table(
        ["列一", "列二", "列三"],
        [
            ["单元格", "单元格", "单元格"],
            ["单元格", "单元格", "单元格"],
        ],
        [0.30, 0.35, 0.35], small=True))
    s.append(caption("表 1-1　表格编号从章号起"))
    return s
