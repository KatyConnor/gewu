# 安装形态决策（STD-HUB-001 V1.8 · 集中布局）

> 本文件回答三个安装问题：治理资产放哪里（P1 集中项目级）、不变量放哪里（P2 全局级）、
> 技能面放哪里（P3 技能直装）。划分依据 = 各规范判型记录（meta/records/spec-*.yaml）
> 的 level_tests / type_model 字段——**安装形态是 META 层级轴的物理落地**。
> V1.8 起项目根目录只保留 `AGENTS.md` 一个治理文件 + `.standards/` 一个治理目录。
>
> **形态与技能的关系（V1.9.13 澄清·先读）**：要用体系技能 ≠ 必装 P3——
> P1 与 P2 均为**全量安装**，40 张技能卡随 `.standards/skills/` 一并就位，由调度器经
> 角色卡三级加载（卡→技能→模板）**强制路由**使用；P3 是把技能**单独**拎进 AI 工具
> 自带技能池的独立通道（意图检索触发·无门禁），适用于不装治理的场景。
> 组合矩阵与加载路径对照见《使用手册》V1.9.13 增补页。

## 一、P1 集中项目级安装（默认）

项目根只出现两个治理入口，其余全部收进统一目录：

```
your-project/
├── AGENTS.md            ← 唯一根级治理文件（统一调度器：注册表/加载/裁决/接力）
├── .standards/          ← 唯一治理目录（隐藏，与项目代码物理隔离）
│   ├── AETC.md  ATLAS.md  LPU.md  LR.md  OPS.md  SDLC.md
│   ├── AEGIS.md  META.md  UCSS.md  UTSS.md  CODING.md
│   ├── audit/  atlas/  lpu/  lr/  ops/  roles/  skills/  aegis/
│   ├── meta/  ucss/  utss/  code-review/  ui-design/  coding-rules/
│   ├── install-profiles.md（本文件）· suite_check.py
│   └── TRIGGER-MATRIX.md 位置说明：触发矩阵随调度器分发（02_统一调度入口）
└──（运行时状态目录——按规范语义在根级懒创建，见 §四）
```

安装命令（两条）：

```bash
cp 02_统一调度入口/AGENTS.md  your-project/
cp -r 03_规范本体/.standards  your-project/
# 校验（在项目根执行）：python3 .standards/suite_check.py → 全项 PASS
```

**语义保证**：调度器注册表/触发面/互斥六方/接力链/门禁全部不变，
仅入口路径由 `X.md` 改为 `.standards/X.md`——触发执行与检查行为 1:1 保持。

## 二、P2 全局级安装（V1.9.0 · 独立形态与叠加形态并存）

**P2 独立（V1.9.0 新）**：全局装一次，**无项目层的项目直接获得完整治理**（调度+机制齐全；留痕按项目落）：

```bash
./install-global.sh --platform claude-code   # 或 opencode / generic
```

| 件 | 位置 | 内容 |
|---|---|---|
| 全局规范根 | `~/.std-hub/` | AGENTS.md + .standards/ 全量镜像（4 checker+trigger_check+模板+判型） |
| 平台路由卡 | `~/.claude/CLAUDE.md` 等全局槽位 | GLOBAL-AGENTS.md（两级探测协议+注册表，幂等注入可卸载） |
| 纪律卡 | `04_平台适配/global/GLOBAL-INVARIANTS.md` | 十三套铁律摘要（零路径引用，可选独立使用） |

**两级探测铁律**：所有 `.standards/` 引用按"项目根优先 → `~/.std-hub/` 兜底"解析——
已装 P1 的项目就近覆盖（版本锁定/定制不受全局升级影响）；无项目层项目走全局兜底。
**运行时产物永远落项目根**（`audit/` 留痕、`.lpu/`、`.aegis/`、`tests/qa/`）——跨项目共享静态规范资产，留痕天然隔离。

**P2 叠加（原语义保留）**：全局槽位纪律卡（GLOBAL-INVARIANTS.md）+ 项目层 P1——纪律提醒全场生效。
两层内容冲突时以项目层为准（单向棘轮：项目层只能更严，不能放松全局纪律）。

**P2 能力边界（诚实声明）**：
- 机制能力与 P1 等价（checker 在全局根可跑、调度器在场）；平台全局槽位**无自动加载的平台**（cursor 等）P2 独立退化——需项目入口（矩阵见 `04_平台适配/global/README.md`）
- 全局根=单一版本：所有无项目层项目共享同版；需版本隔离的项目自装 P1
- 平台路由卡内的两级探测是提示层协议（LLM 遵循），机制层兜底=install-global.sh 内置全局根自检（`cd ~/.std-hub && python3 .standards/suite_check.py` → 176 项全绿）

## 三、P3 技能直装（技能面，混合形态规范的第二形态）

适用：支持 Agent Skills 开放标准的平台（ZCode / Claude Code / 自托管框架）。
判型依据：`form = hybrid` 的规范，其本体即技能，可整目录直装：

```bash
# UCSS 技能面（扫描作业流+规则库）
cp -r 03_规范本体/.standards/ucss  ~/.zcode/skills/code-scan
# UTSS 技能面（测试协议+方法库+模板）
cp -r 03_规范本体/.standards/utss  ~/.zcode/skills/utss
# SDLC 技能池（39 个角色技能模块，V1.1~V1.4 +12：交互设计 3/文档工程 3/运营增长 3/客户成功 3；V1.5 +3 并升版 4；V1.6 九卡深化 18 技能升版；V1.7 文档域补全：+document-visual-design，api-doc-generation 与 user-manual-authoring 升 V1.1 接线 TPL-APIREF/UM/RN）
cp -r 03_规范本体/.standards/skills/*  ~/.zcode/skills/
```

技能直装后仍受项目层 P1 调度器管理（技能面触发 = 意图检索；规范面门禁 = 机制层强制，
两者并存不冲突——正是 META 混合形态的物理体现）。**CI 门禁不受安装形态影响**：
`ucss_gate.py` / `coverage_gate.py` 在流水线中按绝对路径调用，与 Agent 安装层解耦。

## 四、运行时状态目录（留根原则）

以下目录/文件是**项目数据**（判型记录 `repo_bound=true` 的运行时面），
由对应规范激活时在根级创建/使用，不进 `.standards/`：

| 运行时路径 | 归属规范 | 语义 |
|---|---|---|
| `audit/`（config.json + traces/ reports/ reviews/ checkpoints/） | AETC | 留痕账本与项目化风险清单 |
| `atlas/config.yaml` | ATLAS | 对接激活开关（模板在 `.standards/atlas/config.yaml`） |
| `.atlas/` | ATLAS | 本地 WAL |
| `.lpu/`、`docs/lpu/` | LPU | 逆向理解状态与产出 |
| `.lr/` | LR | 重构进度 |
| `.ops/` | OPS | 运维台账 |
| `.aegis/` | AEGIS | 三单/预算/冻结实例 |
| `tests/qa/` | UTSS | 测试五件套落位 |
| `design-tokens.json` | UI | 产品令牌（项目资产） |

**首次激活命令**（按需，仅 AETC 常驻必做）：

```bash
mkdir -p audit/{traces,reports,reviews,checkpoints}
cp .standards/audit/config.json audit/config.json     # AETC 留痕激活
# ATLAS 对接（可选）：mkdir -p atlas && cp .standards/atlas/config.yaml atlas/config.yaml
```

## 五、安装决策矩阵（META 记录驱动）

| 判型字段 | 取值 | 安装动作 |
|---|---|---|
| `level` | dual | P1 必装（默认双层拆法：全局卡 + 项目本体） |
| `level_tests.invariant` | true | 可选 P2 全局卡（纪律摘要） |
| `form` | hybrid | 可选 P3 技能直装（本体=技能） |
| `type_model` | 质量门型/含门禁 | CI 挂对应 gate 脚本（绝对路径） |
| `level_tests.repo_bound` | true | 运行时目录留根（§四懒创建） |
| `type_model` | 横切·条件激活（ATLAS） | 装而不活——复制根级 config 才激活 |

单规范拆装（可拆性 G7）：`.standards/X.md + .standards/x/` 成对复制/删除即可，
调度器注册表同步增删一行；删除后 suite_check 相应项转 FAIL 提示回补。
