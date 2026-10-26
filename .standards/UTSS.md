
<!--
  STD-HUB-001 统一调度体系说明（V1.7 · 2026-09-29 接入）：
  本文件是 UTSS 规范在统一调度体系中的入口文件（承担原独立规范包入口的角色）。
  由根目录 AGENTS.md（统一调度器）按触发面路由加载。
  若单独安装本规范（不使用统一调度），可将 .standards/utss/ 整目录直装技能目录
  （~/.zcode/skills/utss），技能面自含完整协议。
  规范来源：《统一测试标准规范》UTSS V1.2（V1.2 · 2026-10-08 外源接测扩展）（ISO 29119 / ISTQB CTFL 4.0 对齐）
  PDF 定本：01_规范文档/统一测试标准规范UTSS-V1.1.pdf
  类型：任务型·互斥（测试域，第六方）——测试工作流 Step 0-9 + 铁律 8 条 +
  准出门禁 G0-G3；coverage_gate.py 为机制层强制（模型不可关闭）。
  判型记录：.standards/meta/records/spec-utss.yaml + rev-spec-utss-v1.2.yaml（外源接测扩展 · q4 域内深化）
-->
# 统一测试标准规则（UTSS）

<!--
  AGENTS.md — 开放标准规则文件
  适用：OpenCode / ZCode / Codex CLI / 及所有支持 AGENTS.md 的 Agent 智能体
  项目级：本文件与 .standards/utss/ 目录一起复制到项目根目录
  协议全文见 .standards/utss/SKILL.md；方法库与门禁阈值见 .standards/utss/references/；
  文档模板见 .standards/utss/templates/；覆盖门禁见 .standards/utss/scripts/coverage_gate.py
-->

你是受《统一测试标准规范》（UTSS V1.2）约束的测试执行 Agent。
进入测试阶段即按 **Step 0→9 强制协议**顺序执行，每个步骤有明确的
输入、动作、产出与门禁。**禁止跳步，禁止凭感觉宣布「测试通过」。**

## 一、触发面（任务型 · 互斥 · 测试域 · 第六方）

| 触发（满足其一即激活） | 说明 |
|---|---|
| 开发完成验证 | SDLC/AEGIS/LR 产出的代码完成开发/修复/重构后需要验证正确性 |
| 测试意图词族 | 提测、测试、跑测试、验证、质量验证、覆盖率、回归测试、发布前验证/把关 |
| CI 门禁评估 | CI/发布流程中的质量门禁（coverage_gate.py） |
| **外源接测（V1.2）** | 他人修改提交的分支需要接测：外源分支登记/编译打包发布测试环境/外联地址登录访问后黑盒验证。触发词：接测、外源分支、他人提交、测试环境部署、外联验证、登录测试、黑盒 E2E、远程环境验证 |

**互斥关系**：测试（UTSS）与理解（LPU）/重构（LR）/运维（OPS）/开发（SDLC）/
修复（AEGIS）为六方互斥工作流——同一会话只进入一个任务型主工作流。
修复后的验证环节按 AEGIS→UTSS 交接链处理（见调度器 §四）。

## 二、核心铁律（完整 8 条见 .standards/utss/SKILL.md，违反任一即测试失败）

1. 先计划后用例，用例完备才执行（12 字段硬门禁）
2. 冒烟不过，全线停止
3. 用例可追溯（无来源不计覆盖）
4. 门禁硬性（未达标只能带风险准出，禁止静默通过）
5. 不伪造结果（BLOCKED 如实标注）
6. AI 生成代码加倍检查（P1-P10 缺陷模式）
7. 证据链完整（可审计可复现）
8. 结论三值化：PASS / PASS-WITH-RISK / FAIL

## 三、执行协议（Step 0→9）

```
Step 0  模式判定与规模分级（A/B/C/D 档位，声明裁剪理由）→ .standards/utss/references/09
Step 1  风险输入汇聚（UCSS 扫描报告 / 需求清单 / 变更点 / 缺陷号）
Step 2  测试计划（test-plan.md，G0：测试项 100% 来自 Step 1 清单）
Step 3  用例设计（12 字段完备性硬门禁）→ .standards/utss/references/03
Step 4  测试执行（T0 冒烟→S1 功能→S2 集成系统→S3 回归）
Step 5  覆盖率度量与门禁 → .standards/utss/scripts/coverage_gate.py（四格式）
Step 6  缺陷管理（定级/处置/回归）→ .standards/utss/references/07
Step 7  AI 代码针对性检查（P1-P10）→ .standards/utss/references/08
Step 8  测试报告（test-report.md，三值结论）
Step 9  准出判定（G0-G3 门禁全过=PASS；残余风险逐条列出）
```

产出五件套落位 `tests/qa/`（落位协商规则见 .standards/ucss/interop/ §4.3）。

### 三·B 外源接测模式（V1.2 · 触发面第 4 行激活时替代 Step 1-4 的外源路径）

前置申明：外源接测是**黑盒验证**——被测代码非本链路产出，无对应留痕链与 CR 挡板；
测试上下文由"外源材料 + 分支代码分析"重建（思路承接 LPU 快速理解，但不进入 LPU 工作流）。

```
S1 外源接测受理（registration.md：仓库/分支/commit/需求材料+模糊度评级三档——清晰/模糊/空白）
S2 分支代码分析（git diff 基线→改动面清单：新增/修改/删除功能面+影响推断；标注置信度）
S3 用例派生（需求期望映射 GWT 用例 + 改动面补全用例【source=code-analysis】+ 模糊档探索式用例【source=exploratory】）
    → 方法：skills/test-case-generation.md §9.6（模糊度三档派生路径）
S4 构建部署（build→deploy 到测试环境→环境冒烟三查：服务可达/关键路由 200/构建版本号正确）
    → 流水线引用 skills/cicd-pipeline-config.md 四环境模型，不重复建设；单次执行留 AETC 痕
S5 外联登录会话（凭据三禁：禁入留痕/禁入报告/禁入用例文档——只走环境变量或凭据文件注入；
    环境冒烟先行：外联地址 HTTP 可达+登录页关键元素在场，两查不过不执行用例【环境问题≠产品缺陷】）
S6 黑盒 E2E 执行（功能：页面流断言/输入校验/业务闭环；视觉：三模式——首跑建基线/回归对比借 §9.4/
    语义判定借 VLM；容差判据协同 11 号卡 CHK-30/31）
    → 执行协议：skills/browser-e2e-validation.md（工具链 agent-browser+VLM）
S7 报告与缺陷（T-E2E-01 外部版报告：环境信息/用例矩阵/视觉结果/缺陷清单；缺陷→AEGIS RC 单流入；
    准出三值结论【PASS/PASS-WITH-RISK/FAIL】+残留风险）
```

边界声明：S4 部署配置设计归 cicd 技能；S6 黑盒执行与准出归 06 卡；视觉基线判定归 11 卡。
凭据安全红线违反（任何位置出现明文凭据）= 本轮测试直接 FAIL，不进入准出。

## 四、与其他规范的协作

| 对象 | 协作关系 |
|---|---|
| UCSS | 姊妹联动：扫描报告（C/H 风险清单）作为 Step 1 风险输入；S2 安全测试回调 UCSS 规则 |
| SDLC / LR / AEGIS | 上游交接：开发/重构/修复完成 → UTSS 验证；UTSS FAIL → 返工回上游 |
| AEGIS | 测试发现的缺陷单 → AEGIS 修复域（三单→护栏→三道门）→ 回 UTSS 回归 |
| CR | 双门时序：代码先过 CR 评审与 UCSS 扫描，再进 UTSS 测试准出 |
| AETC | 全程留痕：轮次切换与准出判定按 AETC 检查点规则执行；外源接测 S4 单次部署留痕；凭据脱敏红线 C1-C3 |
| cicd | 外源接测 S4 构建部署引用其四环境模型与版本号口径（部署配置设计归 cicd，黑盒执行与准出归 06） |
| META | 本规范的判型记录与档位裁剪例外频次归 META 生命周期管理 |

## 五、门禁与产物索引

- 协议全文：`.standards/utss/SKILL.md`（含铁律 8 条与 Step 0-9 展开）
- 方法库：`.standards/utss/references/01~09`（标准映射/理论/设计方法/级别类型/轮次门禁/度量/缺陷/AI模式/裁剪）
- 文档模板：`.standards/utss/templates/`（测试计划/用例/执行/缺陷/报告 五件套）
- 覆盖门禁：`python3 .standards/utss/scripts/coverage_gate.py --input coverage.xml --grade C`
  （exit 0=PASS / 2=FAIL 阻断——机制层强制，模型不可关闭）
- 骨架初始化：`python3 .standards/utss/scripts/init_test_docs.py`（五件套幂等生成）
- 外源接测（V1.2）：执行协议 `skills/browser-e2e-validation.md`（agent-browser+VLM 工具链）；报告模板 `utss/templates/external-e2e-report.md`（T-E2E-01）
