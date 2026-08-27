# 认知层接线/裁剪决策备忘录（T4.6）

> **日期**：2026-08-27 | **状态**：待数据（决策门：A/B 实验数据满足样本量后）
> **决策对象**：D-5（ReflexionRuntime 简化实现）、D-6（DualSystemRouter 未接线）、Wenshi 三层记忆
> **数据来源**：`GET /api/v1/evaluations/experiment-compare`（T3.4 已交付）

## 一、实验设计（已就绪）

对照组通过 Agent modelConfig JSON 的 `experimentGroup` 字段配置（四组预置）：

| 分组 | 配置要点 | 观测什么 |
|------|---------|---------|
| `baseline` | 纯 ReAct（不配 experimentGroup 亦可作隐式基线） | 成功率/时长/成本基线 |
| `complexity` | 仅启用复杂度路由（L1-L3 预算分级，`agent.engine.budget.*`） | 分级是否降低轻任务成本 |
| `model_route` | 仅启用模型路由（ModelSelector 按复杂度改选模型） | 路由是否提升重任务质量 |
| `full_stack` | 复杂度路由 + 模型路由 + 记忆注入 + 语义缓存 | 全开是否有叠加收益 |

**统计要求**：每组 ≥50 样本、≥3 轮实验周期；Judge 分值（evaluation_record 左联）作为质量口径。

## 二、决策规则（预先承诺，防沉没成本驱动）

设 Δ质量 = full_stack（或单能力组）相对 baseline 的成功率与 Judge 分值提升；
Δ成本 = 相对成本增幅；Δ延迟 = P95 时长增幅。

| 数据形态 | 决策 | 动作 |
|---------|------|------|
| Δ质量 ≥ +5% 且 Δ成本 ≤ +20% | **接线** | D-6 接线：System2 -> PLAN_EXECUTE 运行时 + modelTier 驱动 ModelSelector；D-5 实现真实反思循环（Critic 失败 -> reflect -> 注入重跑 ≤3 轮） |
| Δ质量 < +2% 或 Δ成本 > +50% | **裁剪** | DualSystemRouter 保留日志级观测（docs 标注"仅观测"）；ReflexionRuntime 标 @Beta 移出主线；Wenshi 记忆 SPI 保留、实现进维护态 |
| 介于两者之间 | **缩小范围** | 仅在 L3 复杂任务分组启用（按 experiment-compare 的分组明细判断），L1/L2 走纯 ReAct |

## 三、当前技术事实（决策输入）

1. **ConfidenceGate 简化重载数学下限**（Sprint 2 测试发现）：`evaluate(critic)` 固定项 0.5，
   单 Critic 场景最差只能 RETRY_CHANGE_CONTEXT，永远到不了 ESCALATE_HITL；
   接线时需用全参重载（toolSuccessRate/historicalSimilarity/contractPassRate 真实值）；
2. **Wenshi PoC 自述**（docs/wenshi/WENSHI-POC-REPORT）：H4 整体效果接近纯 LLM，待完整实验；
3. **成本口径已就绪**（T4.1）：session.cost/tokens_* 真实累计 + model_config 单价，
   实验对比的成本列可直接采信。

## 四、接线的技术预留（已就绪，无需预开发）

- D-6 接线点：`ReactAgentExecutor.planExecution` 已产出 `systemChoice.runtimeMode/modelTier`，
  接线即在 `execute/executeStream` 按 runtimeMode 分派 PLAN_EXECUTE 运行时（Sprint 3 已有 RouteConditionEvaluator 等图设施可复用）；
- D-5 接线点：`ReflexionRuntime` 接口位 + `ReflectionEngine` SPI（Wenshi 适配器已存在）；
- 裁剪回滚：裁剪只动实现层文档与标注，引擎 SPI 全保留，重实现成本为增量。
