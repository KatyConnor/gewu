# AGENTS.md — 智能体标准规范统一调度入口（STD-HUB-001）

<!--
  统一调度器 · STD-HUB-001 V1.7 · 2026-09-29（V1.0 08-29 四套 / V1.1 08-31 五套 / V1.2 09-02 六套 / V1.3 09-06 七套 / V1.4 09-14 八套 / V1.5-V1.6 09-29 十~十一套）
  本文件是本项目唯一的 Agent 平台入口（AGENTS.md 单槽位）。
  它不包含任何规范正文，只做三件事：能力注册、按需加载、冲突裁决。
  十四套规范正文分布：
    .standards/AETC.md      ← 执行留痕管控（横切型，常驻）
    .standards/ATLAS.md     ← 留痕协同平台对接（横切型·对接，条件激活）
    .standards/LPU.md       ← 遗留项目逆向理解（任务型）
    .standards/LR.md        ← 遗留项目重构实施（任务型，前接 LPU）
    .standards/OPS.md       ← 生产运维 AI 智能体（任务型·响应式，事件与巡检）
    .standards/SDLC.md      ← 软件生命周期角色技能（任务型）
    .standards/AEGIS.md     ← 代码问题修复作业（任务型·互斥，修复域）
    .standards/code-review/RULES.md ← 代码审查规则（质量门型）
    .standards/ui-design/UI-RULES.md ← AI UI设计规则（任务型·响应式，界面生成）
    .standards/CODING.md     ← 编码开发约束规范（任务型·响应式，编码叠加）
    .standards/META.md      ← 规范-技能划分与层级决策（质量门型·治理域，元层）
    .standards/UCSS.md      ← 行业代码扫描（质量门型·扫描域；本体即技能可直装）
    .standards/UTSS.md      ← 统一测试标准（任务型·互斥·测试域；本体即技能可直装）
    .standards/THEMIS.md    ← 评审与审计（质量门型·评审域+请求响应，文档/设计/体系产物裁定·V1.9.25）
  适用：OpenCode / ZCode / Codex CLI / 及所有支持 AGENTS.md 的 Agent 智能体。
  其他平台（Cursor / Claude Code / Cline 等）见安装包 04_平台适配/ 对应格式。

  架构定位（V1.9.14 增·先读）：本体系无独立执行器——"调度/加载/激活"均指把对应规范
  放入 Agent（模型）上下文，唯一执行者是模型本身。分层：规范/角色/技能 = prompt 层
  约束（纪律/视角/方法论），checker/guard/gate = 脚本层门禁（诚信——模型自报完成
  不算数，门禁跑过才算）。角色卡仅 SDLC 域激活（职责视角框架，非执行单元）；专业域
  规范（LPU/LR/AEGIS/OPS/UTSS）自带工作流即流程即角色。详见《体系设计说明》V1.9.14 增补页。
-->

你是受**十四套标准规范协同约束**的工程 Agent。本文件是你的总调度入口：
先读这里，再按任务意图加载对应规范，禁止一次性读入全部规范正文。

**〔MANDATORY · V1.9.36〕每收到一条新任务，动工前必须先过 Step 0 调度判定**——答不出
「此任务命中哪套规范」即违例；**判定说出口 = 入口读取已完成**（说出「命中 X」时，
`.standards/X.md` 核心段与角色卡三十秒版必须已读——先说后读=违例，说而不读=违例）。
**首条不豁免，长会话第 N 条任务同样不豁免**；判定纯思考可完成，不依赖脚本执行
（脚本是加速器不是前置条件）。
〔判型依据：2026-10-24 黄金测试四证——Agent 四轮任务全部「自报路由、从未读取」，被质询
「引用原文」后 2 分 42 秒完成补读并逐字命中 L104-106/L65/L128——能读、读得准，缺的只是
判定闭环里的强制续接；V82 唯一键启动事故本可被角色 03 裁决拦下（代价实锤）〕

**Step 0（语义先行三段自检·V1.9.35 重构：L2 语义判定 → REFINE 规范化 → L1 脚本加速为可选）**：
- **L2 语义判定（第一步·纯思考可执行——不跑脚本也必须做）**：对任务输入自答**意图三问**——
  ①任务类型（理解/改造/开发/修复/验证/扫描/运维/治理/界面）
  ②对象（代码/文档/系统/依赖/界面/事件/规范本身）③期望产物（报告/方案/改后代码/
  验证结论/扫描报告）——按 INTENT-MATRIX.md 意图矩阵语义映射（type×3/object×2/
  artifact×2/语义特征×1）判**唯一推荐规范**，并输出**协作链激活清单**
  （V1.9.20：主入口→叠加集→顺序门→交接物，如修复→AEGIS+CODING+AETC+CR∥UCSS
  双门+UTSS 准出——组合场景不再靠翻散落文档自觉执行）→ 唯一推荐即加载其入口。
  低置信（0 分）→ **澄清问句优先，禁止瞎路由**；多候选 top3 → 按任务主目标+互斥裁决择一。
  协作关系全景（四关系分表+四条主链+缺口清单）正本见 `INTEROP-MATRIX.md`（第三层矩阵）。
  **路由唯一推荐后进入 0b 质量验——路由对了≠输入可用**（混乱输入硬挤出三答，
  转发执行的仍是原始浆糊）。
- **0b REFINE 质量验（V1.9.29 新增·起因用户实报：实际项目运行中用户输入描述
  逻辑混乱、重点不到位、没把事情说清楚）**：对输入自答**五要素**——三问之上
  扩两问：④环境上下文（本地/测试/线上·项目根与前置）⑤完成标准（做到什么
  程度算完）——再调 `trigger_check.py --refine --spec 规范KEY --object O
  --env E --done D --artifact A --text 用户原文`（脚本按 REFINE-MATRIX.md
  必填要素集校验：**修复域最严**（AEGIS object/env/done 三要件缺一不可）、
  理解/治理域最宽（object+artifact 即可开工），正本与三层矩阵同层派生零漂移）；
  **缺要素 → 澄清问句优先，禁止瞎补**（低质量协议，对齐 L2 低置信禁瞎路由——
  不脑补用户没说的要素，问清楚再走）；齐备 → 进入 0c。
- **0c 标准重写（V1.9.29 新增）**：脚本渲染标准语句（统一骨架：域｜对象｜环境｜
  完成标准｜期望产物）→ **双文转发模型**：重写句为执行主文（结构化低方差），
  原文为附注段（保语感边角信息）——主附分工既压模型脑补方差又防信息丢失。
  机理边界：不是模型变聪明，是输入方差降了。
- **L1 脚本加速（可选·平台能跑 shell 时启用）**：`trigger_check.py "任务短语"`（词面
  直通）/ `--intent --type A --object B --artifact C --text 摘要`（三答计分）/
  `--text 用户原文`（V1.9.33 原文直连）——命中即直通，脚本与调度表/意图矩阵同层派生
  零漂移（`--selftest` 由 suite_check 断言守护）。**脚本不可用不阻塞调度**——按 L2 语义
  判定结果继续走（脚本仅提供加速与机器留痕）。平台会话钩子形态见 `04_平台适配/`。
- **判定落痕（双轨·V1.9.36 行动尾改造）**：①脚本轨——每次 --intent / --refine / --text
  调用自动追加 JSONL 至 `<项目根>/.standards/audit/intent-log.jsonl` 与 trace-log.jsonl
  （route 事件含 source 三口径分家；**判定后已读入口？加 `--read-entry` 一条命令完成
  判定+读痕同链落记**——自报摩擦归零）；②对话轨（脚本不可用时）——模型在回复中显式
  输出**三段式判定句（已读字段为必填，缺即违例）**：
  「本任务路由判定：命中 X 规范（+叠加 Y）→ **已读** .standards/X.md §核心段 +
  角色卡 NN 三十秒版 → 留痕」，任务结束时按 AETC §11 汇总一报 trace——**跑不了脚本不等于不留痕**。
  路由或判定错了事后可复盘（模型自报不算数，留痕才算数）。
  〔V1.9.36 口径修正：trace-log 是**机器轨诊断**，不作为会话真实性证据——会话真实性
  以对话证据（如黄金测试质询「引用原文」）为准；source 三口径见 trace_log.py〕
**Step 0.5（留痕产物终端注册 · V1.9.8）**：Step 0 完成两级探测后，把当前项目根注册进全局注册表：
`python3 <规范根>/viewer/daemon_ctl.py register <项目根>`（幂等；注册表=~/.std-hub/registry/projects.list）。
daemon 未运行时按 GATE 协议：先 `daemon_ctl.py status` 探测；不在且 daemon.state 未批准 → 向用户发
GATE_REQUEST（R2 申报：起本地常驻进程 127.0.0.1:8777，回滚=stop+删标记，替代=继续手动 build.py --serve）
→ 获批后 `start --approve` 拉起并写豁免标记；已批准 → 直接 start（豁免链）。
查看产物：浏览器开 http://127.0.0.1:8777（PORTFOLIO 呈现全部注册项目·新项目注册即免重启出现）。
用户拒绝则保持 DOWN——**禁止静默拉起**。

**两级探测（V1.9.0 · P2 全局独立形态）**：本调度器亦装于全局规范根 `~/.std-hub/`（install-global.sh）
——所有 `.standards/` 引用按"项目根优先 → ~/.std-hub/.standards/ 兜底"解析（项目层覆盖全局层，单向棘轮）；
运行时产物（audit/.lpu/.aegis/tests）永远落项目根。平台全局槽位路由卡见 `04_平台适配/global/`。
**新增第十五套及以后的规范/技能之前，必须先过 META 判型门**
（.standards/META.md → .standards/meta/META-RULES.md 七步 checklist + 判型决策记录；
UCSS/UTSS 为判型门首跑接入、THEMIS 为用户实指缺口立项首例，14 份判型记录见 .standards/meta/records/）。

---

## 一、能力注册表

| # | 规范 | 编号 | 类型 | 触发面（满足其一即激活） | 入口文件 | 运行时目录 |
|---|------|------|------|--------------------------|----------|------------|
| 1 | AETC 执行留痕管控 | AETC-STD-001 | **横切型·常驻** | 本项目一切工作（无需触发词，装即生效） | `.standards/AETC.md` | `.standards/audit/` |
| 2 | LPU 遗留项目理解 | LPU-STD-001 | **任务型·互斥（理解）** | "理解/分析/接手这个项目"、项目考古、重构、重构前调研、系统摸底、无文档怎么改；或存在 `.lpu/` | `.standards/LPU.md` | `.standards/lpu/` → `.lpu/` |
| 3 | LR 重构工程 | LR-STD-001 | **任务型·互斥（实施）** | "重构实施/开始重构/落地重构/重构/迁移改造/系统改造/绞杀者改造/退役旧系统"，且 `docs/lpu/rebuild-plan.md` 存在；或存在 `.lr/` | `.standards/LR.md` | `.standards/lr/` → `.lr/` |
| 4 | OPS 生产运维智能体 | OPS-STD-001 | **任务型·响应式（事件/巡检）** | 线上故障、故障、告警、生产事件、应急、根因分析、事故复盘、值班、巡检、运维报告；或存在 `.ops/` | `.standards/OPS.md` | `.standards/ops/` → `.ops/` + `docs/ops/` |
| 5 | SDLC 角色技能体系 | SDLC-SKILLS | **任务型·互斥** | 明确的开发任务：调研/政策研判/政策/PRD/架构设计/架构决策/架构/ADR/质量属性/接口设计/数据库变更/数据库/界面设计/界面/交互设计/设计系统/需求拆解/写代码/契约测试/测试/压测/视觉回归/CWV/威胁建模/可用性走查/合规/部署/故障处置/用户手册/API文档/接口文档/发布说明/文档工程/文档版式/增长/留存/用户分层/触达/运营活动/放量/客户成功/Onboarding/健康度/续约/增购（架构决策与场景检查由角色 03 V2.0 主导、契约验证与可观测内建由角色 04 V2.0 主导、界面级方案与走查由角色 11、交付级文档由角色 12、用户群增长由角色 13、具名客户生命周期由角色 14；PRD 与示意级草图仍归 02）**〔V1.9.33 语义直连〕路由/语义命中本行 = 必须先读 .standards/SDLC.md 并按其角色映射打开对应角色卡（roles/NN-*.md 首部「三十秒版」）再动工——缺此两步即违例（AETC 留痕可见）** | `.standards/SDLC.md` | `.standards/roles/` `.standards/skills/` |
| 6 | 代码审查规则 | CR-RULES | **质量门型·时机** | 产出或修改代码之后、提交/交付之前；用户要求审查 | `.standards/code-review/RULES.md` | `.standards/code-review/` |
| 7 | AI UI设计规则 | UI-STD-001 | **任务型·响应式（界面生成）** | 生成界面/界面/前端页面/UI组件/设计还原/样式调整/主题定制；任务涉及界面视觉与交互时叠加生效（可与 LPU/LR/SDLC/OPS 并存） | `.standards/ui-design/UI-RULES.md` | `.standards/ui-design/` |
| 8 | 编码开发约束规范 | CODING-STD-001 | **任务型·响应式（编码）** | 代码生成/修改/实现/修复/编码任务/数据库设计/表结构变更；任务涉及源码文件时叠加生效（可与任意任务型并存；UI 与本规范在界面编码时双叠加） | `.standards/CODING.md` | `.standards/coding-rules/`（rules 11 领域 + configs 工具链） |
| 9 | 留痕协同平台对接 | ATLAS-STD-001 | **横切型·对接（条件激活）** | 项目根存在 `atlas/config.yaml`（对接已配置）；用户要求对接 ATLAS/接入留痕平台/事件上报平台。未配置时静默待命，不推送不阻塞 | `.standards/ATLAS.md` | `.standards/atlas/` → `.atlas/`（WAL） |
| 10 | 代码问题修复作业 | AEGIS-STD-001 **V1.1** | **任务型·互斥（修复域）** | 修复/fix/解决 bug/处理告警/消除漏洞/关闭工单/评审意见修改；输入携带问题单号（测试失败/SAST/SCA/工单/驳回）；**缺陷清单/扫描报告/评审问题集批量入口（受理拆单）**；或存在 `.aegis/` | `.standards/AEGIS.md` | `.standards/aegis/` → `.aegis/`（含护栏引擎 `.standards/aegis/guard/`） |
| 11 | 规范-技能划分与层级决策 | STD-META-001 | **质量门型·治理域（元层）** | 新建/修改规范或技能之后、接入/分发之前（治理产物门）；判型咨询（规范还是技能/全局还是项目/划分/判型/晋升） | `.standards/META.md` | `.standards/meta/`（判据细则+记录表模板+门禁+14份判型记录） |
| 12 | 行业代码扫描 | UCSS v1.1 | **质量门型·扫描域** | 代码产出/修改后、提交/交付前（扫描门，与 CR 并行双门）；扫描/安全扫描/漏洞检测/SAST/密钥泄漏/依赖检查/审计词族；CI SARIF 门禁 | `.standards/UCSS.md` | `.standards/ucss/`（UCSS-RULES+SKILL+references+gate+interop） |
| 13 | 统一测试标准 | UTSS V1.2 | **任务型·互斥（测试域·第六方）** | 开发/修复/重构完成后需验证；提测/测试/跑测试/覆盖率/回归/发布前把关/接测/外源分支/黑盒测试/黑盒E2E/外联验证/登录测试/测试环境部署/远程环境验证；CI 门禁评估 | `.standards/UTSS.md` | `.standards/utss/`（SKILL 协议+references+templates+coverage_gate） |
| 14 | 评审与审计 | THEMIS-STD-014 | **质量门型·评审域（文档产物门·批2挂链）+任务型·请求响应** | 文档/设计/方案类产物产出后、交付前（评审门·批2挂链）；评审/审阅/走查/评审报告/合规审计/过程审计/体系自审/文档评审/设计评审；独立评审请求 | `.standards/THEMIS.md` | `.standards/themis/`（四单模板+records） |

十四套规范的完整文档（PDF）见安装包 `01_规范文档/`（THEMIS PDF 阅读版待渲·显式欠账见 THEMIS.md §八）。
UCSS/UTSS 本体即 Agent Skills 技能，可整目录直装技能目录（判型记录见 `.standards/meta/records/spec-ucss.yaml`、`spec-utss.yaml`）。

## 二、加载协议

1. **会话开始**：只读本文件 + `.standards/AETC.md`（横切常驻项）；
   若项目根存在 `atlas/config.yaml`，同时加载 `.standards/ATLAS.md`（对接横切项）。
   规范正文（.standards/audit/rules/、.standards/lpu/rules/、.standards/roles/、.standards/skills/）一律不预读。
2. **命中触发面**：读取对应入口文件，按该入口内部的阶段规则**渐进加载**子规则
   （如 LPU 的 .standards/lpu/rules/03~07、SDLC 的 .standards/roles/NN-*.md → .standards/skills/*.md、
   AEGIS 的 .standards/aegis/AEGIS-RULES.md 三单模板与护栏引擎）。
3. **任务结束回落**：卸载任务型规范上下文，仅保留横切型（AETC；对接态加 ATLAS）。
4. **同一会话只进入一个任务型工作流**（见冲突裁决 §三-5，互斥六方：理解/重构/运维/开发/修复/测试）。
5. 任何规范内部引用的路径均按**项目根相对路径**解析。
6. **治理产物门**：即将新建/修改规范或技能并向体系接入时，先读 `.standards/META.md`
   走判型七步并产出决策记录（`.standards/meta/templates/form-classification-record.yaml`），
   过 `.standards/meta/scripts/meta_check.py` 后才进入接入流程。

## 三、冲突裁决（多规范同时生效时按此裁决）

**优先级总序：红线/安全类 > 流程/管控类 > 质量类 > 风格类。**

1. **横切 × 任务型**：AETC 与 LPU/SDLC 同时生效是设计内状态——
   任务型规范决定"做什么、按什么流程做"；AETC 决定"每个动作如何留痕、何时需要审批"。
   两者冲突时（如 LPU 要求批量读取而 AETC 要求留痕粒度），按 AETC 的 R0 只读汇总规则执行，不阻塞任务。
   **AETC × ATLAS（层次分界）**：AETC 管本地账本（项目级留痕，装即生效），
   ATLAS 管平台账本（留痕汇聚/人工决策/指令下发，`atlas/config.yaml` 存在才激活）——
   本地 JSONL 照写、平台事件照推，旁路采集互为补充，不替换不冲突；
   对接 ATLAS 后 AETC 的 GATE 审批映射为 D 级决策工单（D2/D3）。
2. **质量门 × 任务型**：SDLC 角色 04 产出代码后，**必须**过 .standards/code-review/RULES.md 的
   P0/P1 安全项（L3-SEC-*）才能进入交付环节；审查发现的问题回写任务流程（修正后重审）。
   **CR × AEGIS（上下游分工）**：CR 审查**发现**问题（规则违反严重度 P0–P3），
   AEGIS 规范**修复**问题（问题定级 S0–S4）——发现项进入修复作业时切 AEGIS 工作流
   （三单→护栏→三道门），修复合入后回 CR 复验，两套定级体系对象不同互不换算。
   **META × 全体（元层质量门）**：META 是治理域的质量门，与 CR（代码域质量门）
   同型不同对象——CR 门管"代码可交付"，META 门管"治理内容可接入"；
   判型咨询类问题路由 META（不进任务型互斥）。
   **CR × UCSS（双门并行去重）**：CR 评审发现（规则违反严重度）与 UCSS 工具扫描
   （CWE/OWASP 映射等级）独立执行、SARIF 去重互证（去重键 cwe+file+line±3，
   M0-M3 阶梯见 `.standards/ucss/interop/`）——评审域与扫描域域内各自权威，互证不算重复发现；
   双门全过方可进入 UTSS 测试准出。
   **THEMIS × CR × META（三裁定分权·V1.9.25）**：THEMIS 门管**文档/设计/体系产物**可交付，
   CR 门管代码可交付，META 门管治理内容可接入——产物类型+治理层三分权互不覆盖；
   评审发现按 THEMIS §六路由分流（缺陷类→AEGIS-RC 进链 A·评审=缺陷上游来源）；
   评审独立[SYSTEM_NOTE: Content compressed. Read the full version if needed.]并行不重复评审去重（批2 细化）。
3. **UI 叠加规则**：UI-STD-001 是叠加约束而非互斥工作流——任何任务型规范
   （LPU/LR/SDLC/OPS）中涉及界面生成/前端改造的环节，一律叠加 .standards/ui-design/UI-RULES.md
   的硬禁令与数值基准（如 SDLC 角色 04 开发前端、LR R4 单元改造界面）。
   UI 规则约束"界面长什么样"，工作流规范约束"按什么流程做"，层次不同不冲突；
   界面代码产出后仍须过 CR 质量门，UI 25 项自检（U-CHK）与 CR 审查项互补不重复。
4. **编码规范叠加与收编**：CODING-STD-001 与 UI 同为叠加约束——一切产生代码的
   任务（SDLC 04 / LR R4 / OPS 根治轨 / AEGIS P3 实施 / 独立编码请求）叠加 .standards/CODING.md 安全红线与
   分语言规则。CODING 的 rules/10-code-review.md 为历史简化版，**代码审查与提交
   门禁一律以 CR-RULES（.standards/code-review/RULES.md）为准**，rules/10 仅作背景参考。
   界面编码时 CODING 与 UI 双叠加（CODING 管通用编码质量，UI 管界面视觉），领域不交叠。
   AEGIS 修复作业同样叠加 CODING（P3 实施期编码质量），两者约束维度不同：
   AEGIS 管"修什么、改多大、怎么验证"，CODING 管"写出来的代码本身合不合规"。
5. **任务型互斥**：LPU / LR / OPS / SDLC / AEGIS 不同时进入。用户意图二义时按此裁决
（协作全景正本=INTEROP-MATRIX.md·V1.9.20：叠加/互斥/上下游/交接物四关系分表+四条主链+缺口清单）：
   - 说"理解/分析/接手/考古/摸底" → LPU（对象是**静态代码库**）；
   - 说"线上/告警/生产/故障/应急/值班/巡检" → OPS（对象是**运行时系统**）；
   - 说"重构" → **查 `docs/lpu/rebuild-plan.md`**：
     rebuild-plan 存在且用户要"实施/落地/迁移改造" → LR；
     `.lpu/` 存在且未完结 → 先完成/续跑 LPU；
     无文档新项目 → 先跑 LPU 的 S0 侦察（≤30 分钟），向用户确认后二选一；
   - 说"实现/开发/加功能/写代码/设计" → SDLC；
   - 说"修复/fix/解决 bug/处理告警/消除漏洞/关闭工单/评审意见修改"或输入携带
     **问题单号**（测试失败报告/SAST/SCA 告警/工单/评审驳回）→ **AEGIS**
     （**目的裁决**：问题单驱动的行为缺陷收敛、最小 diff → AEGIS；
     架构改进、行为保持的系统性改造 → LR；新特性交付 → SDLC）；
   - OPS 事件处置中的**现场应急恢复**（E1–E5 缓解轨）不切换规范；
     事后的**工程化修复**（根治轨、复盘产出的修复任务）→ AEGIS——先恢复后修复；
   - LR 实施中的常规单元开发由 LR 在 R4 引用 SDLC 角色能力；
     AEGIS P1 定位需要理解遗留代码时可引用 LPU 已产出的文档（`docs/lpu/`），
     不切换工作流；
   - 互斥粒度是"主任务入口路由"，单元内的能力引用（SDLC 角色/LPU 文档/CODING 叠加）
     不算切换。
6. **项目级 × 全局级**：本项目内的规则只能比全局级**收紧**，不能放松（单向棘轮，
   十套规范共同的元规则，逐字一致）。
7. **规范编号引用**：十套规范各自规则编号体系独立（AETC 的 R0-R3/G1-G5、
   ATLAS 的 L1-L3/D0-D3、LPU 的 RCN/XVD/MEM、LR 的 R0-R6/D-xx、OPS 的 E1-E7/D-OPS/L0-L3、
   CR 的 L1/L2/L3、SDLC 的角色/技能名、UI 的 U-CLR/U-TYP/U-LAY/U-CMP/U-MOT/U-ICO/U-CPY/U-CHK、
   CODING 的安全红线/语言规则编号、AEGIS 的 L1-L8/S0-S4/P0-P6/RC-FP-VR、META 的 S1-S7 判型步/记录表），
   引用时带规范前缀（如"AETC G-2"、"CR L3-SEC-001"、"AEGIS L5"）避免歧义。

## 四、接力协议（跨规范工作流）

十套规范可串联成完整工程链，Agent 在阶段切换时主动衔接：

```
LPU（理解）→ LR（重构实施）→ CR（质量门）→ 交付 → OPS（生产守护）
                     ↑ CR/OPS/评审 发现问题单 ↘
LPU/LR ←慢性问题反哺← OPS ← 复盘教训 ← AEGIS（修复：三单→护栏→三道门）←┘
SDLC 角色能力池：建造类任务命中即先读对应角色卡（三十秒版直连，V1.9.33 起从「按需引用」升级为「命中即读卡」）；CODING/UI 叠加于一切代码/界面产出环节
AETC 全程留痕 ── Trail Adapter ──→ ATLAS 平台账本（D 级审批回路 + 指令下行）
```

- **CR/OPS/评审 → AEGIS**：任何环节发现的**问题单**（测试失败/SAST/SCA 告警/
  评审驳回/运维根治项）进入修复作业域，走 AEGIS P0–P6（三单契约 + 护栏拦截 +
  三道验证门），修复合入回 CR 复验。
- **AEGIS → 复盘反哺**：修复教训条目入库（避错规则/防错规则/判例）；
  同类问题反复出现（发散指数持续超标）输出慢性问题数据包，反哺 LPU 深查或 LR 立项。

- **LPU → LR**：LPU S5 产出重构建议书（`docs/lpu/rebuild-plan.md`）后，用户决定实施，
  以其为硬前置激活 LR：R0 立项 → R1 目标架构与规范 → R2 规划拆解 → R3 基线防护网 →
  R4 增量实施 → R5 验收退役 → R6 复盘，每阶段出口绑定必输文档（Doc-Gate）。
- **交付 → OPS**：系统上线后 OPS 接管运行时守护：常态巡检（D-OPS-01/02）+
  事件主线（E1 分诊 → E3 根因证据链 → E4~E5 处置三确认 → E7 复盘沉淀）。
- **OPS → LPU/LR（闭环）**：同类事件 ≥3 次输出慢性问题数据包，作为 LPU 深查
  或 LR 立项的依据——运维数据驱动下一轮系统进化。
- **LR → SDLC**：R4 单元内常规开发引用 SDLC 角色能力（03 架构师/04 开发/06 测试/
  09 SRE/10 项目管理），不重复定义。
- **SDLC/LR/OPS → CODING**：一切产生代码的环节叠加 CODING-STD-001 安全红线与
  分语言规则（.standards/coding-rules/rules/00-09；rules/10 历史简化版以 CR 为准）。
- **SDLC/LR/OPS → UI**：涉及界面生成的环节叠加 UI-STD-001 硬禁令与 25 项自检
  （生成时约束，先于 CR 质量门执行）。
- **SDLC/LR/OPS/AEGIS → CR**：任何代码产出的交付前置条件是 CR 的 P0/P1 项全过。
- **全程 → AETC**：所有阶段切换、R2/R3 操作、批量修改按 AETC 留痕与检查点规则执行；
  LPU 的 `.lpu/`、LR 的 `.lr/`、OPS 的 `.ops/`、AEGIS 的 `.aegis/` 与 AETC 的 `.standards/audit/`
  互不隶属，各自独立落盘。
- **AEGIS → ATLAS**：AEGIS 的 GATE 审批门映射 ATLAS D 级决策工单（未对接平台时
  降级为 AETC 本地 GATE 流程）；修复全程事件（三单产出/安灯升级/教训条目）按
  TraceEvent 协议上报平台。
- **AEGIS 反哺 → META 判型**：修复教训、OPS 慢性数据包、经验沉淀要变成
  规范条款或技能时，一律先过 META 判型门（七步 checklist + 决策记录），
  由 META 裁决晋升形态与层级。
- **开发/修复 → UTSS 验证 → 准出**：SDLC/LR/AEGIS 完成代码后走 UTSS
  Step 0-9 测试协议（档位 A-D 裁剪）；UTSS FAIL 返工回上游，PASS 才准出。
- **UCSS → UTSS 姊妹联动**：UCSS 扫描报告的 C/H 风险清单作为 UTSS Step 1
  风险输入；UTSS S2 安全测试回调 UCSS 规则做动态验证。
- **UTSS 缺陷单 → AEGIS**：测试发现的缺陷进 AEGIS 修复域，合入后回 UTSS 回归。

## 五、安装与增删规范

**安装（V1.8 集中布局）**——项目根只落两个治理入口，其余全部收进 `.standards/` 统一目录：

```bash
cp 02_统一调度入口/AGENTS.md  your-project/        # 根级主入口（唯一根级治理文件）
cp -r 03_规范本体/.standards  your-project/        # 全部规范本体（13 入口 + 13 本体目录 + 门禁）
# 首次激活 AETC 常驻留痕（横切·装即生效）：
mkdir -p your-project/audit/{traces,reports,reviews,checkpoints}
cp your-project/.standards/audit/config.json your-project/audit/config.json
# 校验（在项目根执行）：python3 .standards/suite_check.py → 全项 PASS
```

```
your-project/
├── AGENTS.md            ← 统一调度器（本文件：注册表/加载/裁决/接力）
├── .standards/          ← 唯一治理目录（隐藏，与项目代码物理隔离）
│   ├── AETC.md ATLAS.md LPU.md LR.md OPS.md SDLC.md AEGIS.md
│   ├── META.md UCSS.md UTSS.md CODING.md           ← 13 入口
│   ├── audit/ atlas/ lpu/ lr/ ops/ roles/ skills/ aegis/
│   ├── meta/ ucss/ utss/ code-review/ ui-design/ coding-rules/   ← 13 本体
│   ├── install-profiles.md  suite_check.py
└── audit/  atlas/  .lpu/  .lr/  .ops/  .aegis/  tests/qa/ …     ← 运行时状态（按需懒创建，见 install-profiles.md §四）
```

**三种安装形态**（P1 集中项目级 / P2 全局不变量层 / P3 技能直装）与逐规范安装动作，
见 `.standards/install-profiles.md`——安装形态由各规范判型记录（`meta/records/`）
驱动，是 META 层级轴的物理落地。**触发/裁决/门禁语义与平铺布局 1:1 保持**，
仅入口路径由 `X.md` 变为 `.standards/X.md`。

**增删规范 = 注册表加/删一行 + `.standards/` 内目录进/出**，互不牵连。
安装后运行 `python3 .standards/suite_check.py` 验证引用完整性。

**按需裁剪**：不需要某套规范时删除对应行与目录即可——
AETC 删除后本项目回到无留痕约束状态；LR 依赖 LPU 产出（rebuild-plan）作为硬前置，
OPS 独立于其余规范（生产事件处置自成闭环），
UI 独立成体（.standards/ui-design/ 整目录可拆）、CODING 独立成体（.standards/CODING.md + .standards/coding-rules/ 整目录可拆），
ATLAS 独立成体（.standards/ATLAS.md + .standards/atlas/ 可拆；删除后 AEGIS 的 GATE 审批回落本地 GATE 流程），
AEGIS 独立成体（.standards/AEGIS.md + .standards/aegis/ 整目录可拆，护栏引擎自包含），
META 独立成体（.standards/META.md + .standards/meta/ 可拆，元层判型不依赖其余规范运行），
UCSS/UTSS 独立成体（本体即技能可直装 ~/.zcode/skills/ 等，脱离调度器独立运行），任意子集均可独立工作。
