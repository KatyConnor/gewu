# UTSS 独立准出记录 — agent 引擎升级（AE-PLAN-2026-11）

> **准出对象**：分支 fix/sandbox-security-storage，8234a71..b2e7c2c（17 提交，阶段1可靠性清偿+阶段2能力接线+阶段3工程质量）
> **准出档位**：S3 回归档（轻量）｜**执行者**：UTSS 角色 06 独立准出代理（与实现方分离，只读）
> **日期**：2026-11-14 ｜ **上游链**：AEGIS 三单（FIX-20261112-×）→ 本准出；THEMIS RPT-002 已闭环

## 一、独立执行证据（角色 06 亲跑，非采信自报）

| 门 | 命令 | 结果 |
|---|---|---|
| 单元回归（engine） | mvn -pl gewu-agent-engine test | **Tests run: 305, Failures: 0, Errors: 0** + BUILD SUCCESS |
| 覆盖门（JaCoCo 棘轮） | mvn -pl gewu-agent-engine verify | **All coverage checks have been met** |
| 回归（application -am，排除 3 类基线既有失败） | mvn test（排除 E2E/Performance/SandboxProxyControllerRoute） | **Tests run: 214, Failures: 0, Errors: 0** + BUILD SUCCESS |
| 扫描（UCSS，30 主源文件） | pyscan scan + ucss_gate | **全 severity 零发现**（Critical/High 0 · Medium 0 · Low 0） |

三档合计 **519 用例 0 失败**。

## 二、抽样核对（3/3 与实现方自报一致）

1. ✅ PipelineModeHandler 子 Walk 三路守卫（pause L1001/1030、cancel L1060/1078、fail L1101/1117 委托父链）
2. ✅ ToolExecutor 高危 SQL fail-closed（L46 模式、L119 参数匹配拒绝）
3. ✅ AgentSkillSectionContributor 技能段单一实现点（L59 公共渲染、L51 空值短路、与 wenshi 链路共用注释）

## 三、准出结论

**通过（PASS）**——Step 4 回归档证据链完整可复现。

## 四、偏差与风险（如实）

1. 执行环境：mvn 不在 PATH，显式 JDK 21 + Maven 3.9.16 执行（非实质偏差）。
2. 3 类排除测试（E2E/Performance/SandboxProxyControllerRoute）为分支基线既有失败——基线 worktree（8234a71 无本批改动）已于 2026-11-11 实证复现，本准出**未独立复证基线**（采信 11 日实证，风险低）。
3. 修复方自验证与独立准出双层证据链完整；准出覆盖至 b2e7c2c，之后的提交需增量准出。
