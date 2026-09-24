
<!--
  STD-HUB-001 统一调度体系说明：
  本文件由原规范包的项目级 AGENTS.md 更名而来，内容未改动。
  在统一调度体系中，它由根目录 AGENTS.md（统一调度器）按触发面路由加载；
  平台入口槽位（AGENTS.md）已被统一调度器占用，本文件不再直接作为平台入口。
  规范本体目录（lpu/ 等）按根相对路径引用，与本文件配合工作。
  若单独安装本规范（不使用统一调度），可将本文件改名回 AGENTS.md 作为独立入口。
-->
# LPU 遗留项目逆向理解与重构文档化规范 · 项目级执行层

> 规范编号 LPU-STD-001 · V1.0 · 本文件为 LPU 规范的项目级母版（平台无关）
> 放置位置：目标遗留项目根目录。凡支持 AGENTS.md 的智能体（OpenCode/ZCode/Codex CLI 等）直接读取本文件；
> 其他平台（Cursor/Claude Code/Cline 等）使用 `04_平台适配/` 中对应格式的等价文件。

---

## 0. 本文件是什么

你（Agent）正在接手一个**可能没有任何文档、没有技术资料**的历史遗留项目。LPU 规范定义了一套
标准化的逆向理解与文档化工作流：从**代码、数据库、功能实现、业务知识、业务流程**五个维度
全方位理解项目，输出**可直接支撑重构决策**的 8 份标准文档。

**执行原则（一句话版）**：每走一步都留下证据与进度；无证据不结论；大项目增量推进；
产出必须让一个从未接触过本项目的新工程师能据此做出重构决策。

## 1. 触发条件

满足任一条件即进入 LPU 工作流：

1. 用户表达"理解/分析/接手"本项目的意图（含同义词：项目考古、老项目梳理、逆向分析、
   重构前调研、系统摸底、没文档怎么改、这个项目是干嘛的）；
2. 项目根目录存在 `.lpu/` 工作目录（进入**续跑模式**，见 §6）；
3. 用户直接指令"执行 LPU / 项目考古 / 逆向理解"。

触发后第一动作：读取 `.lpu/progress/PROGRESS.md`。
- 存在且未完结 → **续跑**（按 MEM-002 恢复协议）；
- 不存在 → **初始化**：将 `lpu/init/` 复制为 `.lpu/`（config.json + progress/ + findings/），然后从 S0 开始。

## 2. 五维理解模型（分析什么）

| 维度 | 代号 | 分析对象 | 核心产出 |
|---|---|---|---|
| 代码维 | C | 架构分层、模块依赖、调用链、技术债热点 | 架构图、依赖图、热点清单 |
| 数据维 | D | 表结构、ER 关系、数据流、热表画像 | ERD、数据字典、数据流图 |
| 功能维 | F | 功能清单、入口点、API、页面/路由/定时任务 | 功能-入口映射表、API 文档 |
| 知识维 | K | 领域术语、显式/隐式业务规则、魔法值 | 术语表、业务规则库 |
| 流程维 | P | 业务流程、状态机、跨系统交互时序 | 流程图、状态迁移表、时序图 |

**铁律：五维缺一不可。** 只做代码分析不做业务流程还原 = 没有理解这个项目。
维度详细规则见 `lpu/rules/03` ~ `07`。

## 3. 六阶段工作流（怎么干）

```
S0 侦察 → S1 骨架 → S2 五维深潜 → S3 交叉验证 → S4 成文 → S5 重构建议
  半天      1天       按模块增量      随发现进行       滚动成文      最后收敛
```

| 阶段 | 目标 | 规则文件 | 完成标志 |
|---|---|---|---|
| S0 侦察 | 技术栈/规模/入口/数据库连通性 | `lpu/rules/01-recon.md` | RECON 报告写入 findings |
| S1 骨架 | 目录结构→模块清单→依赖图 | `lpu/rules/02-skeleton.md` | 模块清单+依赖图 |
| S2 深潜 | 五维逐模块分析 | `lpu/rules/03`~`07` | findings 库按维度填充 |
| S3 验证 | 三角互证、冲突消解 | `lpu/rules/08-cross-validation.md` | 置信度复核完成 |
| S4 成文 | 8 份标准文档 | `lpu/rules/09-documentation.md` | 8 份文档齐备 |
| S5 建议 | 技术债清单+重构路线图 | `lpu/rules/10-rebuild.md` | 重构建议书交付 |

各阶段不是严格串行：S2 每完成一个模块即可滚动进入 S3/S4（增量成文）。

## 4. 三条核心纪律（红线）

1. **无证据不结论**：任何写进输出文档的判断（架构结论、业务规则、数据关系）必须带证据锚点
   ——`文件路径:行号`、表名/列名、或配置项。凭"看起来像"下结论 = 违规。
2. **置信分级**：每个结论标注 A（代码直接证据）/ B（≥2 独立来源推断）/ C（单源推断）。
   **C 级结论不得作为重构决策依据**，只能列入"待人工确认清单"。
3. **只读原则**：分析过程不修改任何业务代码与数据。所有生成物只写入 `.lpu/` 与 `docs/lpu/`。
   数据库只读 schema 与统计信息，**任何行级业务数据不得导出落盘**。

另两条红线（见 `lpu/rules/00-core.md`）：大项目必须增量推进（禁止单会话贪全）；进度必须先落盘再继续。

## 5. 产出物（8 份重构就绪文档）

全部输出到 `docs/lpu/`，模板在 `lpu/templates/`：

| # | 文档 | 文件名 | 模板 | 支撑的重构决策 |
|---|---|---|---|---|
| 1 | 项目全景概览 | overview.md | templates/overview.md | 立项判断、团队交接 |
| 2 | 系统架构文档 | architecture.md | templates/architecture.md | 架构演进路线 |
| 3 | 数据库设计文档 | database.md | templates/database.md | 数据迁移方案 |
| 4 | 功能清单与API文档 | features-api.md | templates/features-api.md | 功能范围对齐 |
| 5 | 业务流程文档 | business-processes.md | templates/business-processes.md | 流程再造 |
| 6 | 业务规则与术语表 | rules-glossary.md | templates/rules-glossary.md | 规则迁移完整性 |
| 7 | 技术债与风险清单 | debt-risks.md | templates/debt-risks.md | 重构优先级 |
| 8 | 重构建议书 | rebuild-plan.md | templates/rebuild-plan.md | 重构策略决策 |

## 6. 工作目录与断点续跑

```
.lpu/
├── progress/PROGRESS.md   # 状态机：当前阶段/已完成模块/下一步
├── findings/              # 发现库：按维度分文件，S2 的原始积累
│   ├── C-code.md          #   代码维发现
│   ├── D-data.md          #   数据维发现
│   ├── F-function.md      #   功能维发现
│   ├── K-knowledge.md     #   知识维发现
│   ├── P-process.md       #   流程维发现
│   └── X-conflicts.md     #   交叉验证冲突记录
└── config.json            # 项目画像：技术栈/规模/数据库连接方式
```

**续跑协议**：每次会话结束前（或每完成一个模块），更新 `PROGRESS.md`（记录：当前阶段、
已覆盖模块、未决问题、下一步计划）。下次会话从该文件恢复，**禁止重复分析已覆盖模块**
（除非 PROGRESS 标记该模块结论被推翻）。

## 7. 自检与交付

8 份文档成文后，运行自检：

```bash
python3 lpu/scripts/lpu_check.py --docs docs/lpu/ --findings .lpu/findings/
```

自检覆盖：文档齐备性、证据锚点格式、置信度标注、模板章节完整性、C 级结论隔离。
自检通过（exit 0）才允许向用户声明"分析完成"。

## 8. 规则文件索引

| 文件 | 内容 | 对应规范章节 |
|---|---|---|
| `lpu/rules/00-core.md` | GEN 通用规则 + MEM 记忆断点规则 + 红线五条 | 第三章 |
| `lpu/rules/01-recon.md` | RCN 侦察规则 | 第四章 |
| `lpu/rules/02-skeleton.md` | SKL 骨架规则 | 第四章 |
| `lpu/rules/03-deep-dive-code.md` | DIV-C 代码维 | 第五章 |
| `lpu/rules/04-deep-dive-data.md` | DIV-D 数据维 | 第五章 |
| `lpu/rules/05-deep-dive-function.md` | DIV-F 功能维 | 第五章 |
| `lpu/rules/06-deep-dive-knowledge.md` | DIV-K 知识维 | 第五章 |
| `lpu/rules/07-deep-dive-process.md` | DIV-P 流程维 | 第五章 |
| `lpu/rules/08-cross-validation.md` | XVD 交叉验证规则 | 第六章 |
| `lpu/rules/09-documentation.md` | DOC 成文规则 | 第七章 |
| `lpu/rules/10-rebuild.md` | RBH 重构建议规则 | 第八章 |

加载优先级：本文件（总纲）→ `00-core.md`（必读）→ 当前阶段对应规则文件。
输出传递：findings 库（原始证据）→ 8 份文档（成文结论）→ rebuild-plan（最终决策依据）。
