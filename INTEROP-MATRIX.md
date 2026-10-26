# INTEROP-MATRIX — 规范协作关系全景正本（协作链批 · V1.9.20）

> 与 TRIGGER-MATRIX（词面）/ INTENT-MATRIX（语义）配对的第三层正本：
> 前两个管"**何时进入谁**"，本矩阵管"**进入后如何协作**"——14 套规范间的
> 叠加/互斥/上下游/交接物四关系 + 四条主链路全景。
> 原则：**只汇总显式化既有规则，不发明新规则**（汇总自 AGENTS.md 冲突裁决段
> 与各规范内部互操作段；派生零漂移：trigger_check.py 运行时解析本文件）。
> 起因用户实问（2026-10-14）：修复要排查+留痕+编码多规范联动——组合场景靠模型
> 读散落文档自觉执行，与语义路由批之前的"隐式自发"同病，本批将其正本化+链式推荐。

## 一、四关系分表

### 1.1 叠加关系（并行生效·不互斥）

| 基座 | 叠加于 | 条件 | 层次分界 |
|---|---|---|---|
| AETC | **全体规范**（常驻） | 装即生效，无需触发 | 管每个动作如何留痕；任务型管做什么 |
| ATLAS | AETC（对接态） | 项目根 `atlas/config.yaml` 存在 | ATLAS 平台账本 / AETC 本地账本旁路互补；对接后 GATE→D 级工单 |
| CODING | 产码五方：SDLC04 / LR R4 / OPS 根治轨 / AEGIS P3 / 独立编码请求 | 任务涉及源码文件 | 约束代码质量红线；工作流规范约束流程 |
| UI-STD | 界面四方：SDLC 04/11 / LR R4 界面改造 / 独立界面请求 | 任务涉及界面视觉交互 | 界面编码时与 CODING 双叠加 |
| CR | 产码后五方（同 CODING 基座） | 代码产出/修改之后、交付之前（时机门） | P0/P1 安全项必过 |
| THEMIS | 文档产出方：SDLC 01/02 PRD、03 设计、LPU 报告、LR 方案、AEGIS FP 单、体系文档（V1.9.25·批2 挂链） | 文档/设计/方案类产物产出后、交付前（产物门·批2 接交接物） | 管文档/设计/体系产物可交付（裁定域·独立性红线）；CR 管代码——产物类型分界 |
| UCSS | 同 CR 基座 | 扫描门（与 CR 并行双门） | SARIF 去重互证（键 cwe+file+line±3·M0-M3） |

### 1.2 互斥组（任务主入口单选·六方）

`LPU(理解) / LR(实施) / OPS(运维) / SDLC(开发) / AEGIS(修复) / UTSS(测试)`
——同一会话只进一个任务型主入口；用户意图二义按主目标裁决（理解→LPU、实施→LR…）。
META 判型咨询**不进互斥**（咨询非任务）。

### 1.3 上下游（发现→流转→闭环）

| 上游 | 交接物 | 下游 | 门禁强度 |
|---|---|---|---|
| CR 审查 | 问题集（P0–P3 严重度） | AEGIS 受理拆单 | 语义衔接（定级体系不换算） |
| AEGIS 修复 | 三单+合入代码 | CR 复验 | 语义衔接（修复合入回 CR 重审） |
| CR+UCSS 双门 | 双 PASS 结论 | UTSS 准出 | **硬门**（双门全过才准出） |
| LPU 理解 | `rebuild-plan.md` | LR 入口 | **硬门**（文件不存在 LR 不进） |
| OPS 根治轨 | 复盘报告→工单 | AEGIS（切链 A） | 语义衔接 |
| META 判型 | 判型决策记录 | 新规范接入 | **硬门**（meta_check 过才接入） |

### 1.4 显式无关系对（防漏标注）

AEGIS×UTSS（修复与测试互斥但链内先后衔接）、LPU×AEGIS（理解与修复不直连，经 SDLC/OPS 转接）、
ATLAS×任务六方（不直接对接任务型，经 AETC 汇聚）。

## 二、四条主链路（组合执行协议·模型按此走全链）

### 链 A：问题修复链（主入口 AEGIS）
```
AEGIS 三单受理（拆单→护栏）→ P3 实施【叠加 CODING+AETC 常驻】
→ CR 复验(P0/P1) ∥ UCSS 扫描（双门并行·SARIF 去重）→ UTSS 准出 → 回落（保留 AETC）
交接物：三单（修复/验证/关闭）→ CR 复验结论；SARIF 报告
```

### 链 B：新功能开发链（主入口 SDLC）
```
SDLC 0.2 路由选角色（00→14）→ 角色 04 编码【叠加 CODING+界面时 UI+AETC 常驻】
→ CR(P0/P1) ∥ UCSS 双门 → UTSS 准出 → 回落
交接物：PRD/ADR/接口契约（角色间·02→03→04 等）
```

### 链 C：重构链（LPU→LR 联动）
```
LPU 理解【叠加 AETC】→ 产出 rebuild-plan.md（LR 硬门）→ LR 分期实施 R4【叠加 CODING+界面时 UI】
→ CR ∥ UCSS 双门 → UTSS 准出（互斥切换期回归）→ 回落
交接物：rebuild-plan.md → LR 改造单元清单；SARIF 报告
```

### 链 D：运维事件链（主入口 OPS）
```
OPS 事件响应（止血轨不产码→复盘）／根治轨产码 → 切链 A（工单输入）
【全程叠加 AETC；根治轨编码叠加 CODING】
交接物：事故复盘报告 → 根治工单（入 AEGIS 三单）
```

> 链共性：AETC 全程常驻留痕（R 分级）；产码即叠 CODING；交付前 CR∥UCSS 双门；双门过才 UTSS 准出；
> 任务结束回落仅留横切。**六方互斥约束的是主入口，链内先后衔接不受互斥限制**（如链 A 内
> AEGIS 实施后过 UTSS 准出是链内环节，非并行进入）。

## 三、缺口清单（调研产出·按优先级排队·逐批收口）

| # | 缺口 | 优先级 | 本批状态 | 后续批次 |
|---|------|--------|----------|----------|
| 1 | 组合场景靠模型读散落文档自觉执行（②型缺口） | 高 | **本批闭环**：正本化+--intent 链式推荐+组合链回归 | — |
| 2 | 交接物存在性门禁：rebuild-plan.md/META 判型/双门→UTSS 是硬门✓；三单关闭/CR 复验结论无脚本断言 | 中 | 显式化清单 | 门禁批（candidate） |
| 3 | SARIF 去重键（cwe+file+line±3）有 interop 文档无独立校验脚本 | 中 | 显式化 | 引擎批（candidate） |
| 4 | 13×13 全矩阵稀疏关系未逐对显式标注（防漏靠 §1.4 抽样） | 低 | 抽样标注 | 治理批（candidate） |

> 上限声明：本批**不改各规范本体内部规则**——正本汇总+调度层链式推荐，本体零改动；
> "完美协作"按棘轮逐批逼近，不做一次性终态设计。

## 四、机读链表（trigger_check.py load_chains 解析源）

| 链 | 主入口 | 叠加集 | 顺序门 | 交接物 |
|---|--------|--------|--------|--------|
| 链A修复 | AEGIS | CODING,AETC | CR∥UCSS双门→UTSS准出 | 三单;CR复验结论;SARIF |
| 链B开发 | SDLC | CODING,UI(界面时),AETC | CR∥UCSS双门→UTSS准出 | PRD;ADR;接口契约 |
| 链C重构 | LPU | CODING,UI(界面时),AETC | rebuild-plan硬门;CR∥UCSS双门→UTSS准出 | rebuild-plan.md;改造单元清单;SARIF |
| 链C实施 | LR | CODING,UI(界面时),AETC | CR∥UCSS双门→UTSS准出（互斥切换期回归） | 改造单元清单;SARIF |
| 链D运维 | OPS | AEGIS(根治轨切链A),AETC | 根治轨产码过双门 | 事故复盘报告;根治工单 |

> `--intent` 唯一推荐后若主入口命中本表 → 输出"组合链激活清单"（叠加+顺序门+交接物）并落痕同走。
> 回归：`CHAIN_SELFTEST` 四链用例（修复→AEGIS+链A 全要素 / 开发→SDLC+链B / 重构→LPU+链C / 运维→OPS+链D）。

## 五、交接物注册表（V1.9.21 门禁批·handoff_check.py 解析源）

| 交接物 | 键 | 探测模式 | 链 | 阶段 | 强度 |
|---|---|---|---|---|---|
| AEGIS三单 | aegis-3slips | .standards/aegis/slips/*.md≥3 | 链A | post-fix | 硬 |
| CR复验结论 | cr-recheck | .standards/audit/cr-recheck*.{md,json} | 链A | post-merge | 硬 |
| SARIF扫描 | ucss-sarif | .standards/scan/*.sarif | 链ABCD | post-code | 硬 |
| rebuild计划 | lpu-plan | rebuild-plan.md | 链C | pre-lr | 硬 |
| 事故复盘 | ops-rc | .standards/ops/*复盘*.{md,json} | 链D | post-event | 硬 |
| META判型 | meta-rec | milestones/*.yaml | 全 | pre-access | 硬 |
| THEMIS评审报告 | themis-rpt | .standards/themis/records/RPT-*.md≥1 | 全 | post-doc | 硬 |

> 探测模式为**调度层约定**（自各本体交接物描述显式化；项目可按本体约定覆盖目录后重跑）。
> 门禁语义：`handoff_check.py --chain A --stage post-fix --project <根>` 探测缺失 → FAIL 报警
> 列缺失清单（报警不阻断业务，阻断与否由链上顺序门裁决——本工具管"断了体系报警"）。
