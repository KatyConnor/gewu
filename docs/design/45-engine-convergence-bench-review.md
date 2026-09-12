# 引擎收敛与模型路由终审报告（基准评测数据驱动）

> **文档编号**：45 | **版本**：V1.0 | **日期**：2026-09-12
> **决策依据**：docs/design/43 预承诺规则 + 本地基准评测实测数据（120 次对话）
> **前置**：docs/design/44 门 1（引擎收敛）/ 门 4（认知 D-5/D-6）——本报告给出终审结论

---

## 一、基准评测数据（2026-09-12，120/120 全成功）

**方法**：固定题集 20 题（知识 QA 6 / 代码 6 / 分析 4 / 规划 4）× 3 组 × 2 轮；同步链路；同模型 glm-5.3-flash（ark-code 供应商）；LLM-as-Judge 评分（0-10）；执行账本自动落库。

| 分组 | 变量 | 成功 | 评分均值 | 评分 stdev | 平均时长 |
|------|------|------|---------|-----------|---------|
| legacy（基线） | ReactAgentExecutor 直通 | 40/40 | 5.64 | 1.63 | 35.8s |
| route_on | legacy + modelRouteEnabled | 40/40 | 5.94 | 1.92 | 37.3s |
| wenshi | WenshiReasoningEngine | 40/40 | 5.61 | 1.63 | 84.3s |

**配对差异（同题 r2，n=20）**：
- legacy vs route_on：route_on 在 20/20 题上评分 ≥ legacy（均值差 +0.30，SE 0.21）
- legacy vs wenshi：均值差 +0.38（legacy 略优），正向仅 3/20

**分类维度细分（judge 分数按题类）**：代码生成与任务规划类三组接近；知识 QA 类 wenshi 偶有 10 分/9 分高分但也有 2 分低分（方差大）；分析类三组接近。

---

## 二、终审结论（按 43 号预承诺规则）

### 结论 1：模型路由（route_on）——**暂不接线，保留开关**

Δ质量 +5.3%、Δ时长 +4.3%——表面满足接线阈值，**但实测路由从未生效**：
- `ModelRouter.route` 依赖 `gewu.llm.routing.models` 模型特征注册表——**环境未配置**，路由返回 null，实际保持默认模型；
- route_on 与 legacy 走完全相同的代码路径，+0.30 分差（SE 0.21）**在评分噪声区间内**；
- 结论：+5.3% 是 Judge 方差不显著差异，**不构成接线证据**。

**处置**：`modelRouteEnabled` 请求级开关保留（已实现）；接线前提 = 配置 `gewu.llm.routing.models` 模型特征（至少 3 档：轻量/标准/强力）后重跑基准，届时按同一规则复评。

### 结论 2：wenshi 引擎——**裁剪（生产流式路由回 legacy）**

Δ质量 -0.4%（无增益）+ Δ时长 +135.6%（2.4 倍）——**双重不达标，明确落入裁剪区间**。

**执行**：`gewu.wenshi.routing.stream` 默认值 wenshi → **legacy**（已执行，配置注释引用本报告）。

**保留与复评路径**（非死刑）：
- Wenshi SPI 全保留（MemoryStore/MemoryRouter/Planner/SolverRouter/Critic 均为可插拔组件）；
- 裁剪的直接原因是**知识库为空**（wenshi_semantic_fragment 0 行——知识层没有可注入的内容，三层增强无从发挥）；
- **复评触发条件**：向知识层灌入领域文档（KnowledgeIngestion）并填充经验库后，按同一题集重跑——若 Δ质量 ≥+5% 恢复接线。

### 结论 3：认知层 D-5/D-6（DualSystemRouter/Reflexion）

数据不足以单独决策（被 wenshi 整体裁剪掩盖）。处置维持 44 号文档：DualSystemRouter 保留观测态；Reflexion 保持简化实现。D-6 的"System1/2 选择"逻辑经本基准验证**分类正常**（intentType/复杂度分级输出合理），待 wenshi 复评时一并决策。

---

## 三、本轮基准评测的工程产出

1. **评测基础设施**（可复用）：跑批器 + 20 题固定题集 + LLM-as-Judge 补评 + 分析器（43 号规则自动对比）；
2. **修复链**（跑批过程暴露，全部已修）：
   - XSS 过滤器破坏内嵌 JSON（`809fb82`）
   - agent_tool 缺列 / agent_execution JSON 列错配（schema 漂移，`ad9b37c`）
   - Wenshi 经验复用毒丸致全量拒答（`c9965d8`）——**用户原始投诉的最底层根因**
   - 多轮上下文注入缺失（`4730ffa`）
   - judge 供应商停用导致评分降级 + totalScore 字段对齐（`7880fb0`）
3. **接口长期运行挂起**问题已定位记录（Tomcat 连接处理层，重启恢复；jstack 已存档）——评估 Tomcat 版本升级列入后续。

---

## 四、遗留与复评触发器

| 事项 | 触发条件 | 责任 |
|------|---------|------|
| wenshi 复评 | 知识层灌入领域文档 + 经验库填充 | 平台负责人 |
| 模型路由接线复评 | `gewu.llm.routing.models` 三档特征配置 | 平台负责人 |
| interface 长运行挂起深挖 | 复发时抓 jstack + 连接快照 | 平台负责人 |
| Tomcat 版本升级评估 | Spring Boot 3.2.x → 3.3.x 升级窗口 | 平台负责人 |
