# 企业级多场景 Agent 智能体架构设计 — 落地评估与可实施方案

> 本文档分两部分：**第一部分**对附件《企业级多场景 Agent 智能体架构设计 v1.0》进行落地可行性评估；**第二部分**给出经过裁剪与务实化处理后、可直接落地实施的企业级 Agent 架构设计。

---

## 第一部分：原架构文档落地可行性评估

### 1.1 文档整体评价

原文档是一份**高质量的参考架构（Reference Architecture）**，在概念完整性、覆盖面和方法论体系上表现优秀。其核心贡献——三元组抽象（节点/边/状态）、四环协同、双图解耦、锚点对抗、L1-L4 场景分级——在理论层面自洽且具有前瞻性。

但作为**落地实施方案**存在显著差距：文档停留在"架构语义层"描述，缺少接口契约、数据模型、组件交互协议、具体代码结构等工程落地必需的细节。三大设计支柱中的"双图解耦"和"锚点对抗"在多数企业场景中属于过度设计，实施成本远超收益。

**一句话评价：作为"思考地图"优秀，作为"施工图纸"不合格。**

### 1.2 设计亮点与可继承部分

| 设计元素 | 评价 | 可继承性 |
|---------|------|---------|
| 三元组抽象（节点/边/状态） | 将 Agent 行为统一为图结构，框架无关，表达力强 | ✅ 直接继承，作为核心抽象 |
| 五种控制原语（分支/并行/汇合/重试/HITL） | 覆盖工作流编排的核心需求，借鉴成熟工作流引擎实践 | ✅ 直接继承 |
| L1-L4 场景分级 | 渐进增强理念正确，避免了"一刀切" | ✅ 继承分级思想，调整各级定义 |
| 可观测性四支柱（指标/日志/追踪/LLM追踪） | 在传统三支柱上增加 LLM 专属追踪，务实 | ✅ 直接继承 |
| 多模型路由策略 | 按任务复杂度/成本/延迟动态选模型，生产必需 | ✅ 直接继承 |
| 重试策略矩阵（区分失败类型） | 区分幂等性、副作用，比简单重试专业 | ✅ 直接继承 |
| 四级异常处理（重试/降级/转人工/熔断） | 分级策略清晰，触发条件明确 | ✅ 直接继承 |
| 合规四维度（数据/模型/决策/审计） | 对齐 EU AI Act、NIST AI RMF 等法规 | ✅ L4 场景继承 |
| 提示注入防护 + 工具调用安全 | 覆盖 Agent 特有安全风险 | ✅ 直接继承 |

### 1.3 落地可行性分析（按 L1-L4 分级）

#### L1 个人开发者场景 — 可行性：★★★★★

- **技术选型务实**：LangGraph + OpenAI API + SQLite，500 行代码可实现，月成本 ~$20
- **架构裁剪合理**：仅用三元组 + 控制原语，不引入治理体系
- **落地无障碍**：与当前社区主流实践完全一致
- **结论**：可直接落地，无需调整

#### L2 小型团队场景 — 可行性：★★★★☆

- **技术栈合理**：Docker Compose + LangGraph + PostgreSQL + Redis + Milvus + Langfuse
- **潜在问题**：引入 Temporal 作为工作流引擎对 2-10 人团队偏重，Temporal 的运维复杂度（需要独立数据库、Worker 管理、版本兼容）可能超出团队能力
- **建议调整**：L2 阶段用 LangGraph 内置的状态持久化即可，Temporal 推迟到 L3
- **结论**：基本可行，需轻量化调整

#### L3 企业级场景 — 可行性：★★★☆☆

技术选型层面合理（Temporal + K8s + Milvus + Neo4j + Kafka + Prometheus + Grafana + Jaeger + Langfuse + vLLM），但存在三个核心问题：

**问题一：四环协同的工程复杂度过高**

文档定义了运算环（毫秒-秒）、评估环（秒-分钟）、治理环（分钟-小时）、审计环（小时-天）四个独立环路。实际工程中：
- 四个环路需要四套独立的调度、存储、部署体系
- 环路间的数据同步（Trace API / Metric API / Policy API / Audit API）需要四个独立服务
- 评估环的"归因分析"和治理环的"策略下发"在当前技术成熟度下难以自动化
- **实际成本**：仅四环协同的基础设施就需要 3-5 人的专职平台团队维护

**问题二：双图解耦的实际收益不明**

"工作图（在线执行）"与"改进图（离线优化）"分离，本质上是 ML 系统的"在线推理 vs 离线训练"分离。但：
- Agent 系统的"优化"主要是提示词调优和模型切换，不像 ML 训练那样需要大规模离线计算
- 维护两套图定义 + 同步机制（"执行轨迹导出"与"策略版本注入"）增加了认知负担
- 多数团队的"离线优化"频率很低（每月几次），不值得为此建立独立图系统
- **实际成本**：双图同步逻辑容易出错，调试困难

**问题三：锚点对抗机制难以落地**

"锚点用例集 + 对抗性指标"在概念上类似回归测试，但：
- LLM 输出的非确定性使得"期望输出"难以定义——同一输入的不同输出可能都正确
- "对抗性指标"需要为每个场景设计专属指标，工程量大
- 在实际企业中，多数团队用"人工抽检 + 用户反馈"即可发现效果退化
- **实际成本**：锚点用例集的维护本身就是一个持续的工程任务

**结论**：L3 的技术栈可行，但三大支柱需大幅简化才能落地

#### L4 行业专有场景 — 可行性：★★☆☆☆

- 合规要求（WORM 存储、模型卡/数据卡、影响评估、红蓝对抗）方向正确且有法规依据
- 但"联邦学习 + 安全多方计算"在当前技术成熟度下属于研究阶段，多数企业无法实施
- "关键决策强制人工审批"与"Agent 自治化"存在根本矛盾，需要明确的边界定义
- **结论**：合规部分可行，联邦协作部分需降级为"数据驻留 + API 联邦"

### 1.4 关键风险与缺陷清单

| # | 风险/缺陷 | 等级 | 影响 | 根因 |
|---|----------|------|------|------|
| R1 | **缺少接口契约与数据模型** | 🔴 高 | 无法直接编码实现 | 文档停留在概念层，未定义 API schema、状态数据结构、节点接口协议 |
| R2 | **三大支柱过度设计** | 🔴 高 | 实施成本远超收益 | 四环/双图/锚点在 80% 的企业场景中不需要完整实现 |
| R3 | **技术栈过重** | 🟡 中 | 运维成本高、团队门槛高 | L3/L4 推荐 10+ 组件，需专职 SRE 团队 |
| R4 | **L2→L3 升级跨度过大** | 🟡 中 | 升级失败风险高 | 从 Docker Compose 直接跳到微服务 + K8s + 四环协同，缺少过渡形态 |
| R5 | **成本估算缺失** | 🟡 中 | 预算不可控 | 文档未给出各级别的月度成本估算（基础设施 + Token + 人力） |
| R6 | **联邦协作不切实际** | 🟠 低 | L4 难以落地 | 联邦学习/安全多方计算在 Agent 场景的工程成熟度不足 |
| R7 | **缺少竞品/开源对标** | 🟠 低 | 可信度打折 | 文档自称"本架构"但未提供参考实现或 PoC 验证 |
| R8 | **图示缺失** | 🟠 低 | 理解困难 | PDF 中 8 张架构图在文本提取中不可见，影响可读性 |

### 1.5 评估结论

| 维度 | 评分 | 说明 |
|------|------|------|
| 概念完整性 | 9/10 | 三元组 + 三支柱 + 场景分级形成自洽体系 |
| 行业洞察 | 8/10 | 痛点识别准确，趋势判断合理 |
| 技术选型 | 7/10 | 选型方向正确，但偏重 |
| 落地可行性 | 5/10 | 缺少工程细节，三大支柱需简化 |
| 实施指导性 | 4/10 | 路线图过于理想化，缺少具体交付物定义 |
| **综合** | **6.6/10** | **优秀的参考架构，需大幅裁剪方可落地** |

---

## 第二部分：可落地实施的企业级 Agent 架构设计

> **设计哲学**：保留原架构的优秀抽象（三元组、控制原语、场景分级），将三大支柱从"核心架构"降级为"可选增强"，用工程上可验证的机制替代概念性描述，提供可直接编码的接口定义与数据模型。

### 2.1 设计原则（务实化）

| 原则 | 内涵 | 与原文档的差异 |
|------|------|---------------|
| **三元组为核** | 节点-边-状态是唯一核心抽象，所有能力围绕它构建 | 保持一致 |
| **渐进增强** | 从单进程到微服务，能力按需叠加 | 保持一致，但增加 L2.5 过渡级 |
| **可观测优先** | 任何节点执行必须产出 Trace，可观测性是 Day-1 需求 | 保持一致 |
| **反馈闭环替代四环** | 用"执行→采集→评估→反馈"单一闭环替代四环 | **简化**：四环→单环 |
| **回归基线替代锚点对抗** | 用标准回归测试集 + 抽样人工评审替代锚点对抗 | **简化**：锚点对抗→回归基线 |
| **在线/离线分离可选** | 双图解耦降级为 L3+ 可选能力 | **简化**：核心支柱→可选增强 |
| **成本可算** | 每个级别必须能估算月度成本 | **新增** |

### 2.2 核心抽象：三元组

#### 2.2.1 节点（Node）

节点是工作流的最小执行单元。每个节点实现统一接口：

```python
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any, Optional

@dataclass
class NodeContext:
    """节点执行上下文，由编排引擎注入"""
    workflow_id: str          # 工作流实例 ID
    node_id: str              # 当前节点 ID
    trace_id: str             # 全链路追踪 ID
    state: dict               # 工作流状态（读写）
    meta: dict                # 元状态（只读：版本、时间戳、决策轨迹）
    config: dict              # 节点配置
    model_router: Any         # 模型路由器（按需注入）
    memory_store: Any         # 记忆存储（按需注入）
    tool_gateway: Any         # 工具网关（按需注入）

@dataclass
class NodeResult:
    """节点执行结果"""
    success: bool
    output: Any               # 输出数据，写入 state
    error: Optional[str] = None
    retryable: bool = False   # 是否可重试
    metadata: Optional[dict] = None  # 决策依据、置信度等审计信息

class BaseNode(ABC):
    """所有节点的基类"""
    node_type: str            # 节点类型标识

    @abstractmethod
    async def execute(self, ctx: NodeContext) -> NodeResult:
        ...

    def get_retry_policy(self) -> dict:
        """返回重试策略：max_retries, backoff, idempotent"""
        return {"max_retries": 3, "backoff": "exponential", "idempotent": False}
```

**六类核心节点**（在原文档八类基础上合并，减少概念冗余）：

| 节点类型 | 职责 | 典型实现 |
|---------|------|---------|
| **感知 Perception** | 接收请求、解析输入、格式校验、安全过滤 | API 网关 / 消息消费者 |
| **路由 Routing** | 意图识别、分支决策、子图调度 | LLM 意图分类 / 规则引擎 |
| **推理 Reasoning** | 规划、推理、生成——LLM 的核心调用 | LLM + CoT / ReAct |
| **执行 Execution** | 工具调用、API 请求、外部操作 | Function Calling |
| **记忆 Memory** | 读写记忆、状态持久化、检索增强 | 向量库 / KV 存储 |
| **输出 Output** | 格式化、流式返回、质量检查 | 模板引擎 / 流式 SSE |

> **合并说明**：原文档的"决策"与"规划"合并为"路由"和"推理"；"反思"降级为推理节点的可选后置步骤，不作为独立节点类型——多数场景下反思逻辑可以内联到推理节点中，强制独立反而增加编排复杂度。

#### 2.2.2 边（Edge）

```python
from enum import Enum
from typing import Callable, Optional

class EdgeType(Enum):
    SEQUENTIAL = "sequential"   # 顺序边：A 完成后执行 B
    CONDITIONAL = "conditional" # 条件边：根据条件表达式选择路径
    PARALLEL = "parallel"       # 并行边：同时触发多个节点
    MERGE = "merge"             # 汇合边：等待多个分支完成
    FEEDBACK = "feedback"       # 反馈边：回送到前序节点（循环/迭代）

@dataclass
class Edge:
    source: str                          # 源节点 ID
    target: str                          # 目标节点 ID
    edge_type: EdgeType
    condition: Optional[Callable] = None # 条件边的判断函数
    merge_strategy: Optional[str] = None # 汇合策略：all | majority | first | best
```

#### 2.2.3 状态（State）

```python
@dataclass
class WorkflowState:
    """工作流状态 — 单一真相源（SSOT）"""
    # 工作状态（可变、可回滚）
    work: dict                # 输入参数、中间结果、工具返回值

    # 元状态（追加写、不可篡改）
    meta: dict                # workflow_id, version, timestamps
    decision_trace: list      # 决策轨迹：[{node, condition, choice, confidence, ts}]
    error_trace: list         # 错误轨迹：[{node, error, retry_count, ts}]
```

状态持久化策略：
- **L1**：内存中 `dict`，进程结束时可选写入 JSON 文件
- **L2**：PostgreSQL `workflow_state` 表 + Redis 热状态缓存
- **L3**：PostgreSQL（强一致状态）+ Redis（缓存）+ 对象存储（大对象/Trace）

### 2.3 分层架构

```
┌─────────────────────────────────────────────────────────────────┐
│  应用层 │ 智能客服 / 代码生成 / 金融风控 / 数据分析 / 知识助手      │
├─────────────────────────────────────────────────────────────────┤
│  编排层 │ 工作图引擎 · 控制原语 · 状态管理 · 检查点 · HITL         │
├─────────────────────────────────────────────────────────────────┤
│  能力层 │ 模型路由 · 工具网关 · 记忆检索 · 提示词管理              │
├─────────────────────────────────────────────────────────────────┤
│  反馈层 │ Trace 采集 · 指标计算 · 回归基线 · 告警（L2+ 启用）      │
├─────────────────────────────────────────────────────────────────┤
│  基础设施│ PostgreSQL · Redis · 向量库 · 对象存储 · 可观测性栈     │
└─────────────────────────────────────────────────────────────────┘
         横切：安全网关 · 审计日志 · 多租户隔离（L3+ 启用）
```

**与原文档分层的关键差异**：

| 层 | 原文档 | 本方案 | 理由 |
|----|-------|--------|------|
| 应用层 | 保持 | 保持 | — |
| 编排层 | 工作图引擎 + 改进图引擎 + 控制原语 | 工作图引擎 + 控制原语（改进图降为可选） | 双图解耦非核心 |
| 能力层 | LLM节点/工具节点/记忆节点/检索节点 | 模型路由/工具网关/记忆检索/提示词管理 | 将节点类型收敛为能力服务 |
| 治理层 | 评估环/治理环/审计环/安全网关（独立层） | 反馈层（Trace+指标+基线）+ 安全审计横切 | 四环→单反馈闭环 |
| 基础设施 | 保持 | 保持 | — |

### 2.4 关键机制设计

#### 2.4.1 反馈闭环（替代四环协同）

将四环协同简化为**单一反馈闭环**，覆盖 80% 的工程需求：

```
执行（运算） ──→ 采集（Trace + 指标） ──→ 评估（基线对比 + 抽检） ──→ 反馈（告警/调优/回滚）
     ↑                                                                    │
     └────────────────────────────────────────────────────────────────────┘
```

| 阶段 | 频率 | 延迟 | 实现 |
|------|------|------|------|
| 执行 | 每请求 | 毫秒-秒 | 工作图引擎 |
| 采集 | 每请求 | 异步秒级 | OpenTelemetry + Langfuse |
| 评估 | 每批次/每日 | 分钟-小时 | 回归基线测试 + 指标看板 |
| 反馈 | 按需 | 分钟级 | 告警 → 人工决策 → 配置变更/回滚 |

> **与四环的差异**：去掉了独立的"治理环"（策略版本化/灰度发布合并到 CI/CD 流程）和"审计环"（审计降级为 Trace 的只读快照，不独立成环）。需要完整审计能力的 L4 场景，再启用 WORM 存储 + 独立审计服务。

#### 2.4.2 回归基线（替代锚点对抗）

```python
@dataclass
class RegressionCase:
    """回归测试用例"""
    case_id: str
    input: dict                    # 输入
    expected_criteria: dict        # 期望标准（非精确匹配）
    # 例：{"intent": "refund", "min_confidence": 0.8, "must_contain": ["退款"]}
    evaluator: str                 # 评估器：exact | semantic | llm_judge | human
    threshold: float               # 通过阈值

@dataclass
class RegressionReport:
    total: int
    passed: int
    failed_cases: list
    pass_rate: float
    regression_detected: bool      # 对比上次基线是否退化
    comparison_baseline: str       # 对比的基线版本
```

**实施方式**：
1. 为每个场景构建 50-200 条回归用例（从真实请求中采样 + 人工标注）
2. 每次模型切换、提示词变更、重大配置变更后自动运行回归
3. 回归通过率低于阈值（如 90%）则阻止发布
4. 每日抽样 1% 线上请求运行评估，监测效果退化

> **与锚点对抗的差异**：去掉了"对抗性指标"概念，用标准的回归测试 + 线上抽样替代。更贴近工程实践，团队可直接用 pytest + 自定义评估器实现。

#### 2.4.3 模型路由

```python
class ModelRouter:
    """多模型路由器 — 根据任务类型、成本、延迟动态选择模型"""

    ROUTING_RULES = {
        "complex_reasoning": {"model": "claude-3.5-sonnet", "fallback": "gpt-4o"},
        "general_chat":      {"model": "deepseek-v3",       "fallback": "qwen-max"},
        "code_generation":   {"model": "deepseek-coder",    "fallback": "claude-3.5"},
        "simple_classification": {"model": "qwen-turbo",    "fallback": "deepseek-v3"},
        "privacy_sensitive": {"model": "local-qwen-14b",    "fallback": None},
    }

    async def route(self, task_type: str, ctx: NodeContext) -> str:
        rule = self.ROUTING_RULES.get(task_type, self.ROUTING_RULES["general_chat"])
        # 检查预算门控
        if ctx.config.get("budget_limit") and self._over_budget(ctx):
            return self._cheapest_available()
        # 检查主模型可用性
        if await self._is_available(rule["model"]):
            return rule["model"]
        # 降级到 fallback
        return rule["fallback"] or self._cheapest_available()
```

#### 2.4.4 工具调用网关

```python
class ToolGateway:
    """统一工具调用网关 — 权限控制 + 审计 + 幂等性"""

    async def invoke(self, tool_name: str, params: dict, ctx: NodeContext) -> dict:
        # 1. 权限检查
        self._check_permission(tool_name, ctx)
        # 2. 危险操作确认
        if self._is_dangerous(tool_name):
            await self._require_confirmation(tool_name, params, ctx)
        # 3. 幂等性检查
        idempotency_key = self._gen_key(tool_name, params, ctx)
        if cached := await self._check_idempotent(idempotency_key):
            return cached
        # 4. 执行 + 审计
        result = await self._execute(tool_name, params)
        await self._audit_log(tool_name, params, result, ctx)
        await self._cache_idempotent(idempotency_key, result)
        return result
```

#### 2.4.5 HITL（人工介入）

```python
@dataclass
class HITLRequest:
    """人工介入请求"""
    request_id: str
    workflow_id: str
    node_id: str
    context: dict          # 决策上下文：输入、推理过程、候选方案、风险评估
    options: list          # 操作选项：approve / reject / modify / escalate
    sla_seconds: int       # SLA 超时时间
    timeout_action: str    # 超时动作：auto_approve / auto_reject / escalate

class HITLManager:
    async def request_human(self, req: HITLRequest) -> dict:
        """暂停工作流，等待人工决策"""
        await self._persist_pause_state(req)  # 持久化暂停状态
        await self._notify_reviewer(req)      # 通知审核人
        result = await self._wait_for_decision(req)  # 阻塞等待（支持超时）
        return result
```

### 2.5 场景分级与技术选型（精简版）

#### L1 — 个人开发者（轻量级）

| 维度 | 选型 | 月成本估算 |
|------|------|-----------|
| 运行形态 | 单进程 Python | ~$5（云主机） |
| 编排引擎 | LangGraph（内置状态持久化） | 免费 |
| 模型服务 | OpenAI / DeepSeek API | ~$20-50（Token） |
| 状态存储 | SQLite | 免费 |
| 记忆 | 会话内 dict + 可选 ChromaDB | 免费 |
| 可观测性 | print 日志 + Langfuse 免费版 | 免费 |
| 治理 | 不启用 | — |
| **合计** | | **~$25-55/月** |

#### L2 — 小型团队（标准级）

| 维度 | 选型 | 月成本估算 |
|------|------|-----------|
| 运行形态 | Docker Compose（单机） | ~$50-100 |
| 编排引擎 | LangGraph + LangGraph Checkpoint（PostgreSQL） | 免费 |
| 模型服务 | 模型路由（DeepSeek + Qwen + OpenAI 混合） | ~$200-800 |
| 状态存储 | PostgreSQL + Redis | ~$20（托管） |
| 记忆 | Qdrant / Milvus（单机） | ~$10 |
| 可观测性 | Langfuse（自部署）+ 基础 Prometheus | 免费 |
| 反馈层 | 回归基线（pytest + 自定义评估器） | 免费 |
| 治理 | 速率限制 + 预算门控 | 免费 |
| **合计** | | **~$280-930/月** |

> **关键调整**：不引入 Temporal，用 LangGraph 的 PostgreSQL Checkpoint 处理状态持久化。Temporal 推迟到 L3。

#### L2.5 — 过渡级（新增，解决 L2→L3 跨度过大问题）

| 维度 | 选型 | 说明 |
|------|------|------|
| 运行形态 | 小型 K8s（3-5 节点）或 Docker Swarm | 引入容器编排但控制规模 |
| 编排引擎 | LangGraph + Temporal（仅用于长任务/HITL） | Temporal 仅处理需要人工审批的长流程 |
| 模型服务 | 模型路由 + 1 台 vLLM GPU 节点（高频模型本地化） | 商业 API + 本地推理混合 |
| 状态存储 | PostgreSQL（主）+ Redis（缓存） | 与 L2 一致 |
| 记忆 | Milvus（集群）| 升级到集群模式 |
| 可观测性 | Langfuse + Prometheus + Grafana + Jaeger | 完整四支柱 |
| 反馈层 | 回归基线 + 每日线上抽样评估 | 自动化效果监测 |
| **定位** | 从"团队产品"到"企业生产"的过渡，验证 K8s + Temporal 的运维能力 | — |

#### L3 — 企业级（完整级）

| 维度 | 选型 | 月成本估算 |
|------|------|-----------|
| 运行形态 | K8s 集群（多节点 + 多可用区） | ~$500-2000 |
| 编排引擎 | Temporal（长任务）+ LangGraph（实时工作流） | 免费（自部署） |
| 模型服务 | vLLM 集群（本地推理）+ 商业 API 混合 | ~$2000-8000（含 GPU） |
| 状态存储 | PostgreSQL（主从）+ Redis 集群 | ~$200-500 |
| 记忆 | Milvus 集群 + Neo4j（图谱，按需） | ~$200-500 |
| 事件日志 | Kafka（高吞吐 Trace 采集） | ~$100-300 |
| 可观测性 | Langfuse + Prometheus + Grafana + Jaeger + ELK | ~$200-500 |
| 反馈层 | 回归基线 + 线上 A/B 测试 + 效果看板 | 免费 |
| 安全 | 安全网关 + 工具权限隔离 + 审计日志 | ~$100-300 |
| **合计** | | **~$3300-12100/月** |

> **关键调整**：不强制启用四环协同和双图解耦。双图解耦仅在"需要频繁离线实验"的场景按需启用。

#### L4 — 行业专有（合规级）

在 L3 基础上叠加：

| 维度 | 选型 | 说明 |
|------|------|------|
| 合规框架 | 等保三级 / HIPAA / GDPR（按行业） | 满足监管 |
| 数据隔离 | 多租户物理隔离 + 数据驻留 | 数据不出域 |
| 审计追溯 | WORM 存储 + 不可篡改日志 | 审计级追溯 |
| 模型治理 | 模型卡 + 数据卡 + 影响评估 | AI 治理合规 |
| 人在环中 | 关键决策强制人工审批 | 高风险决策把关 |
| 联邦协作 | API 联邦 + 数据驻留（**非联邦学习**） | 跨组织协作 |
| 部署 | 私有云 / 混合云 | 数据不出域 |

> **关键调整**：将"联邦学习 + 安全多方计算"降级为"API 联邦 + 数据驻留"。联邦学习在 Agent 场景的工程成熟度不足，API 联邦（跨组织 API 调用 + 各方数据本地化）更务实。

### 2.6 工作图定义（声明式 + 代码混合）

```yaml
# workflow-def.yaml — 声明式工作流定义
workflow:
  id: "customer-service"
  version: "1.2.0"
  description: "智能客服工作流"

  nodes:
    - id: "perception"
      type: "perception"
      config:
        input_validation: true
        injection_detection: true

    - id: "routing"
      type: "routing"
      config:
        model: "qwen-turbo"
        task_type: "simple_classification"

    - id: "memory_retrieval"
      type: "memory"
      config:
        store: "milvus"
        top_k: 5

    - id: "reasoning"
      type: "reasoning"
      config:
        model: "deepseek-v3"        # 由 model_router 动态决定
        task_type: "general_chat"
        enable_reflection: true      # 内联反思

    - id: "output"
      type: "output"
      config:
        format: "stream"
        quality_check: true

  edges:
    - {source: "perception", target: "routing", type: "sequential"}
    - {source: "routing", target: "memory_retrieval", type: "conditional",
       condition: "intent != 'greeting'"}
    - {source: "routing", target: "output", type: "conditional",
       condition: "intent == 'greeting'"}
    - {source: "memory_retrieval", target: "reasoning", type: "sequential"}
    - {source: "reasoning", target: "output", type: "sequential"}

  hitl:
    - trigger: "reasoning.confidence < 0.6"
      node: "reasoning"
      sla_seconds: 3600
      timeout_action: "escalate"
```

### 2.7 端到端时序（以智能客服为例）

```
用户请求 (10ms)
    │
    ▼
[感知节点] 解析输入 + 注入检测 (50ms)
    │
    ▼
[路由节点] LLM 意图分类 (100ms)  ──┐
    │                               │ 并行
    ▼                               │
[记忆节点] 检索历史+知识 (80ms) ───┘
    │
    ▼
[推理节点] LLM 生成回答 (2000ms)
    │
    ├──→ [内联反思] 质量评估 (300ms, 可选)
    │
    ▼
[输出节点] 格式化 + 流式返回 (30ms)
    │
    ▼
响应返回

总延迟: ~2.6s（LLM 调用占 ~85%）
并行优化: 感知后路由与记忆并行，节省 ~80ms
```

### 2.8 可观测性设计

#### Trace 数据模型

```python
@dataclass
class AgentTrace:
    trace_id: str               # 全链路唯一 ID
    workflow_id: str
    workflow_version: str
    user_id: str
    tenant_id: str              # 多租户标识（L3+）
    start_time: float
    end_time: float
    total_duration_ms: float
    status: str                 # success | failed | hitl_paused | timeout

    nodes: list                 # [NodeSpan]
    total_tokens: int
    total_cost_usd: float
    model_calls: list           # [{model, prompt_tokens, completion_tokens, cost, latency}]

@dataclass
class NodeSpan:
    node_id: str
    node_type: str
    start_time: float
    end_time: float
    duration_ms: float
    status: str
    input_summary: str          # 截断摘要，避免存储全量
    output_summary: str
    model_used: str             # 实际使用的模型
    tokens_consumed: int
    cost_usd: float
    error: Optional[str]
    decision: Optional[dict]    # 路由节点的决策依据
    retry_count: int
```

#### 关键指标

| 指标类别 | 指标 | 告警阈值 |
|---------|------|---------|
| 性能 | P95 延迟 | > 5s |
| 性能 | QPS | 低于容量 80% 时告警 |
| 质量 | 任务成功率 | < 90% |
| 质量 | 回归通过率 | < 85% |
| 成本 | 日均 Token 消耗 | > 预算 80% |
| 成本 | 单次调用成本 P95 | > $0.5 |
| 可靠性 | 可用性 | < 99.5% |
| 可靠性 | MTTR | > 30min |
| 安全 | 提示注入检出率 | > 0（任何检出即告警） |

### 2.9 安全设计

```
                    外部请求
                       │
                       ▼
              ┌────────────────┐
              │  安全网关       │  ← 输入净化 + 注入检测 + 限流 + 认证
              └───────┬────────┘
                      │
                      ▼
              ┌────────────────┐
              │  感知节点       │
              └───────┬────────┘
                      │
                      ▼
              ┌────────────────┐
              │  推理/执行节点  │  ← 工具网关：权限隔离 + 幂等 + 审计
              └───────┬────────┘
                      │
                      ▼
              ┌────────────────┐
              │  输出节点       │  ← 输出过滤：内容安全 + 脱敏
              └───────┬────────┘
                      │
                      ▼
                   响应返回

    横切：审计日志（全链路操作记录）· WORM 存储（L4）
```

### 2.10 实施路线图（务实版）

```
阶段一：MVP 验证（1-2 月）          目标：验证核心价值
├── 选择 1 个高价值场景
├── L1 架构：LangGraph + 单模型 + SQLite
├── 实现核心节点：感知→路由→推理→输出
├── 构建初始回归用例集（30-50 条）
└── 准出标准：核心流程端到端跑通，用户愿意持续使用

阶段二：团队产品（2-4 月）          目标：可团队协作运行
├── 升级到 L2：Docker Compose + PostgreSQL + Redis + 向量库
├── 引入模型路由（2-3 个模型）
├── 部署 Langfuse 可观测性
├── 实现状态持久化与检查点
├── 完善异常处理（重试/降级/转人工）
└── 准出标准：稳定运行 14 天，可用性 > 99%

阶段二.五：过渡验证（3-5 月）       目标：验证生产级基础设施
├── 引入小型 K8s 集群
├── Temporal 处理需要 HITL 的长流程
├── 完整可观测性四支柱
├── 回归基线自动化（CI 集成）
├── 预算门控 + 成本告警
└── 准出标准：团队能独立运维 K8s + Temporal

阶段三：企业生产（5-9 月）          目标：生产级高可用
├── L3 架构：多可用区 K8s + vLLM 本地推理
├── 安全网关 + 工具权限隔离
├── 线上 A/B 测试框架
├── 灾备方案
└── 准出标准：稳定运行 30 天，可用性 > 99.5%，通过内部安全审计

阶段四：合规达标（9-15 月）         目标：满足行业监管
├── L4 架构：WORM 存储 + 完整审计追溯
├── 模型卡/数据卡/影响评估
├── 关键决策强制 HITL
├── 红蓝对抗测试
├── 私有云/混合云部署
└── 准出标准：通过第三方合规审计
```

### 2.11 风险控制矩阵

| 风险 | 概率 | 影响 | 对策 | 触发指标 |
|------|------|------|------|---------|
| 模型效果退化 | 高 | 高 | 回归基线 + 线上抽样 + 模型版本回滚 | 回归通过率 < 85% |
| 成本失控 | 中 | 高 | 预算门控 + 模型路由 + 缓存 + 告警 | 日消耗 > 预算 80% |
| 提示注入攻击 | 中 | 高 | 输入净化 + 注入检测 + 工具权限隔离 | 注入检出率 > 0 |
| 状态丢失 | 低 | 高 | 检查点持久化 + PostgreSQL WAL | 工作流恢复失败率 > 1% |
| 长任务超时 | 中 | 中 | Temporal 长任务管理 + 超时熔断 | 任务超时率 > 5% |
| 模型供应商中断 | 低 | 高 | 多模型 fallback + 健康检查 | 主模型错误率 > 10% |
| 过度工程化 | 高 | 中 | 严格按场景级别启用能力，拒绝提前升级 | 架构维护成本 > 业务收益 |

### 2.12 团队配置建议

| 级别 | 团队规模 | 核心角色 | 关键能力要求 |
|------|---------|---------|-------------|
| L1 | 1 人 | 全栈开发者 | Python + LangGraph + LLM API |
| L2 | 2-3 人 | Agent 工程师 + 兼职运维 | + Docker + PostgreSQL + 向量库 |
| L2.5 | 3-5 人 | Agent 工程师 + 平台工程师 + 评估工程师 | + K8s + Temporal + 可观测性 |
| L3 | 5-10 人 | + SRE + 安全工程师 | + 多可用区运维 + 安全加固 |
| L4 | 10+ 人 | + 合规专员 + 审计工程师 | + 行业合规 + WORM + 红蓝对抗 |

---

## 附录 A：原架构三大支柱的裁剪决策记录

### ADR-001: 四环协同 → 单反馈闭环

- **状态**：已批准
- **背景**：原架构定义运算/评估/治理/审计四环，工程复杂度高
- **决策**：简化为"执行→采集→评估→反馈"单环，治理环能力合并到 CI/CD，审计环降级为 Trace 只读快照
- **理由**：80% 的企业场景不需要独立的治理环和审计环；单环覆盖核心反馈需求，复杂度降低 60%
- **后果**：失去独立的策略灰度发布能力（由 CI/CD 替代）和物理隔离的审计能力（L4 时再启用）

### ADR-002: 双图解耦 → 可选增强

- **状态**：已批准
- **背景**：工作图与改进图分离增加认知负担和同步成本
- **决策**：双图解耦从核心支柱降级为 L3+ 可选能力，仅在"需要频繁离线实验"的场景启用
- **理由**：多数团队的离线优化频率低（每月几次），不值得维护独立图系统
- **后果**：高频实验场景需要手动启用双图模式

### ADR-003: 锚点对抗 → 回归基线

- **状态**：已批准
- **背景**：锚点用例集 + 对抗性指标概念复杂，落地困难
- **决策**：用标准回归测试 + 线上抽样评估替代
- **理由**：回归测试是工程团队熟悉的方式，可直接用 pytest 实现；线上抽样覆盖效果退化监测
- **后果**：失去"对抗性指标"的自动化检测能力，依赖人工定义回归用例

### ADR-004: 联邦学习 → API 联邦

- **状态**：已批准
- **背景**：联邦学习/安全多方计算在 Agent 场景工程成熟度不足
- **决策**：L4 的跨组织协作用 API 联邦（跨组织 API 调用 + 各方数据本地化）替代联邦学习
- **理由**：API 联邦在工程上可直接实现，联邦学习需要专门的 ML 基础设施
- **后果**：失去跨组织联合建模能力（当前 Agent 场景需求弱）

---

## 附录 B：技术选型决策清单

使用以下清单逐项确认，每项决策记录理由：

- [ ] **编排引擎**：L1/L2 用 LangGraph，L2.5/L3 引入 Temporal 处理长任务
- [ ] **模型策略**：L1 单模型，L2+ 模型路由（≥2 个模型 + fallback）
- [ ] **状态存储**：L1 SQLite，L2+ PostgreSQL + Redis
- [ ] **向量记忆**：L1 可选 ChromaDB，L2+ Qdrant/Milvus
- [ ] **可观测性**：L1 print+Langfuse免费版，L2+ Langfuse自部署+Prometheus+Grafana
- [ ] **部署形态**：L1 本地，L2 Docker Compose，L2.5 小型 K8s，L3 多可用区 K8s
- [ ] **安全**：L2 基础认证，L3 安全网关+工具隔离，L4 WORM+红蓝对抗
- [ ] **HITL**：L2 可选，L3 推荐，L4 强制（关键决策）
- [ ] **回归基线**：L1 手动，L2 pytest 自动化，L3 CI 集成+线上抽样

---

*文档版本：v1.0 | 评估日期：2026-08-07 | 基于原架构文档 v1.0 评估与裁剪*
