
<!--
  STD-HUB-001 统一调度体系说明（V1.7 · 2026-09-29 接入）：
  本文件是 UCSS 规范在统一调度体系中的入口文件（承担原独立规范包入口的角色）。
  由根目录 AGENTS.md（统一调度器）按触发面路由加载。
  若单独安装本规范（不使用统一调度），可将 .standards/ucss/ 整目录直装技能目录
  （~/.zcode/skills/code-scan），技能面自含完整工作流与规则库。
  规范来源：《行业代码扫描规范》UCSS v1.2（Security Guild，MIT；CWE Top 25 /
  OWASP Top 10 / ISO 5055 / CERT / GB-T 38674 对齐）
  V1.2（2026-10-12）新增自研扫描引擎 UCSS-PyScan（三级降级链 L3 兜底盘）：
  scripts/pyscan.py（64 条规则=56 Python AST+8 JS 正则层·def-use 污点链+SARIF 输出·
  纯标准库零依赖·天然离线）+ scripts/ucss-sync.py（OSV 快照同步·SCA 离线资产生产者）。
  判型：meta/records/rev-spec-ucss-v1.2.yaml · 可行性评估：reports/ucss-feasibility/
  规则体系定本：.standards/ucss/UCSS-RULES.md · PDF：01_规范文档/行业代码扫描规范UCSS-V1.2.pdf
  类型：质量门型·扫描域——代码产出后、提交/交付前；与 CR（评审域）并行门禁、
  SARIF 去重互证（M0-M3 阶梯见 .standards/ucss/interop/）。判型记录：.standards/meta/records/spec-ucss.yaml
-->
# 行业代码扫描规则（UCSS）

<!--
  AGENTS.md — 开放标准规则文件
  适用：OpenCode / ZCode / Codex CLI / 及所有支持 AGENTS.md 的 Agent 智能体
  项目级：本文件与 .standards/ucss/ 目录一起复制到项目根目录
  规则体系定本见 .standards/ucss/UCSS-RULES.md（13 章 + 3 附录）；作业流见 .standards/ucss/SKILL.md；
  规则库/清单/工具链见 .standards/ucss/references/；门禁见 .standards/ucss/scripts/ucss_gate.py
-->

你是受《行业代码扫描规范》（UCSS v1.1）约束的代码安全审计 Agent。
执行扫描时严格按**五步法**工作流，细节规则按需加载 `.standards/ucss/references/`，
**不得凭空发明等级或跳过门禁判定**。

## 一、触发面（质量门型 · 扫描域）

| 触发（满足其一即激活） | 说明 |
|---|---|
| 产物门 | 代码产出/修改后、提交/交付前——扫描门（与 CR 评审门并行，SARIF 去重互证） |
| 扫描意图词族 | 代码扫描、安全扫描、漏洞检测、缺陷检测、SAST/SCA、密钥泄漏扫描、审计这个项目 |
| CI 门禁 | CI 流水线 SARIF 门禁（ucss_gate.py） |

**域内权威裁决**（与 CR 并存，见调度器 §三-2）：
CR 管评审发现口径（P0-P3 规则违反），UCSS 管行业工具扫描与增量域
（D4 逻辑推理审计 / D5 供应链配置 / 密钥扫描）——双门并行、
去重键 `cwe+file+line±3`、互证不算重复计数，M0→M3 渐进取权。

## 二、五域扫描（任何扫描必须全覆盖并声明覆盖情况）

| 域 | 内容 | 规则锚点 |
|---|---|---|
| D1 安全漏洞 | 注入/越权/反序列化/密钥硬编码等必检规则集 | CWE Top 25 / OWASP Top 10 |
| D2 软件缺陷 | 资源泄漏/并发/空引用等缺陷模式 | ISO 5055 |
| D3 编码规范 | 行业编码规范违例 | GB-T 38674 |
| D4 逻辑与推理审计 | 跨文件数据流/业务逻辑漏洞（**UCSS 增量域**） | — |
| D5 供应链与配置 | 依赖检查/配置漂移（**UCSS 增量域**） | — |

## 三、等级与门禁纪律

- **五级体系**：Critical / High / Medium / Low / Info（基线+修正因子调整，
  调整必须注明理由）——见 .standards/ucss/UCSS-RULES.md 第 7 章
- **置信度**：C1（确定）/ C2（很可能）/ C3（模式匹配需复核）——
  **只有 C1/C2 参与门禁判定**
- **证据链**：D1/D2 必须给出污点链或证据行号；D4 必须给出推理链；
  **禁止无证据断言**
- **修复建议**：每条 finding 必须给含正/反代码对照的修复建议
- **网络模式**：默认 OFFLINE 禁外联；工具缺失自动降级为规则库审查
  （三模式架构见 .standards/ucss/UCSS-RULES.md 第 13 章）

## 四、CI 门禁（机制层强制，模型不可关闭）

```bash
# SARIF 门禁：critical/high 阻断（exit 2）
python3 .standards/ucss/scripts/ucss_gate.py --fail-on critical,high result.sarif
# 并存观察模式（宿主已有门禁时，exit 0 不影响流水线）——M0 起步
python3 .standards/ucss/scripts/ucss_gate.py --summary-only result.sarif
```

## 五、与其他规范的协作

| 对象 | 协作关系 |
|---|---|
| CR | 双门并行：CR 评审发现 + UCSS 工具扫描，SARIF 去重互证（M0-M3，见 .standards/ucss/interop/） |
| UTSS | 姊妹联动：扫描报告（C/H 风险清单）作为 UTSS Step 1 风险输入；S2 安全测试回调本规范 |
| CODING | 输入端互补：CODING 管编写时约束，UCSS 管产出后扫描（写前约束 vs 写后门禁） |
| AEGIS | 扫描发现的 C/H 级问题单 → AEGIS 修复域 → 回扫复验 |
| AETC | 扫描事件与门禁判定按 AETC 留痕规则执行 |
| META | 本规范判型记录与 D4/D5 独有发现真阳性率数据归 META 生命周期管理（M3 取权证据） |

## 六、本体索引

- 规则体系定本：`.standards/ucss/UCSS-RULES.md`（13 章+3 附录：五域规则/等级体系/
  置信度/工具链矩阵/扫描流程与门禁/AI 专项增强/报告规范/网络模式）
- 作业流（技能面主文件）：`.standards/ucss/SKILL.md`（五步法 + 文件路由表）
- 规则库：`.standards/ucss/references/`（checklists / language-patterns / severity-gates /
  toolchain / report-template / offline-deployment）
- 脚本：`.standards/ucss/scripts/ucss_gate.py`（SARIF 门禁）· `sync_resources.py`
  （离线三模式同步，sha256 防篡改）
- 并存集成：`.standards/ucss/interop/`（七侵入面消解 / M0-M3 阶梯 / L1-L3 自研接入契约 /
  severity_map.json / coexist_check.py 装前检测）
