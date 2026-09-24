# lr/ — LR-STD-001 遗留项目重构工程规范 · 项目级执行包

> 规范编号 LR-STD-001 · V1.0 · 2026-08-31
> 前置：LPU-STD-001 已完成（docs/lpu/ 8 份文档 + rebuild-plan.md）

## 这是什么

当 LPU 已经"读懂"遗留项目并给出重构建议后，LR 接管**从立项决议到旧系统退役归档**
的完整实施工程：七阶段（R0 决策立项 → R1 目标架构与规范制定 → R2 规划拆解 →
R3 基线防护网 → R4 增量实施 → R5 验收切换 → R6 复盘沉淀），
每阶段出口绑定必输文档（Doc-Gate），由 `scripts/lr_check.py` 自动校验。

## 目录

| 路径 | 内容 |
|---|---|
| `rules/00-core.md` | 五条铁律 + 七阶段总览 + 门禁机制 + AETC 分级协同 |
| `rules/01~07-*.md` | 各阶段规则（目标/动作/门禁/协作） |
| `templates/` | 22 个必输文档模板（文档契约，章节即校验对象） |
| `scripts/lr_check.py` | G1~G8 阶段门禁校验 |
| `init/` | 初始化模板（config.json + progress/PROGRESS.md） |
| `docs/` | 运行时产出落位（D-xx 文档写到这里） |

## 快速开始

1. Agent 触发：用户表达重构实施意图 + `docs/lpu/rebuild-plan.md` 存在
   → 将 `init/` 复制为项目根 `.lr/`，从 R0 开始。
2. 每完成一份文档：填 `templates/` 对应模板 → 存 `docs/lr/D-xx-xx.md`
   → 更新 `.lr/progress/PROGRESS.md` → 跑 `python3 lr/scripts/lr_check.py`。
3. 阶段门禁全 PASS 才可进入下一阶段；R3 未过禁止动旧代码（铁律 1）。

## 与统一调度体系的关系

本包已接入 STD-HUB-001（V1.1）统一调度：根 AGENTS.md 调度器按
"重构实施"触发面路由加载本规范的 `LR.md`。单独使用时，
将 `../AGENTS.md` 更名复制到项目根作为入口即可。
