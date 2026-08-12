# Wenshi（格物知行）—— 新一代大模型架构技术方案

> 版本：v2.0  ·  日期：2026-07-16  ·  状态：PoC 验证方案（待评审）
>
> **核心定位**：验证一种**不依赖（或少依赖）Transformer 权重**的类人智能架构。
> 不是给 LLM 加外挂，而是探索一条"结构化记忆 + 编排推理 + 经验学习"的模型路线。

---

## 0. 为什么重新定义（从 v1.1 到 v2.0 的转变）

### 0.1 之前的定位（v1.1）

```
LLM（DeepSeek/Qwen）+ 外部编排层 = 增强型 Agent
```

LLM 是核心推理引擎，我们做的是"操作系统" —— 给 LLM 加记忆、加工具、加学习。

### 0.2 现在的定位（v2.0）

```
知识记忆层 + 推理思维层 + 学习层 = 新一代大模型本身
```

**这套架构就是模型**。LLM 在新架构中只是一个可选组件（语言接口），不是核心。

### 0.3 对比

| 维度 | Transformer（DeepSeek/GPT） | Wenshi（格物知行） |
|------|---------------------------|-------------------|
| **知识存储** | 权重矩阵（黑盒） | 结构化记忆（白盒） |
| **推理方式** | 前向传播（矩阵乘法） | 算法编排（规划+搜索+规则） |
| **学习方式** | 反向传播（需海量数据+GPU） | 经验积累（每次任务都学习） |
| **可解释性** | 几乎为零 | 完全可追溯（推理轨迹） |
| **增量更新** | 需要重训练 | 实时写入即可用 |
| **成本** | 高（GPU 推理） | 低（算法推理为主） |
| **语言能力** | 强（原生） | 依赖语言接口（LLM/小模型） |
| **逻辑推理** | 中等（幻觉问题） | 强（符号推理确定性强） |

### 0.4 核心创新假设（PoC 要验证的）

| # | 假设 | 验证标准 |
|---|------|---------|
| **H1** | 结构化记忆能替代权重存储知识 | 知识写入后精确召回率 > 95% |
| **H2** | 编排推理能替代前向传播做决策 | 推理过程可追溯、可干预、可审计 |
| **H3** | 经验学习能替代反向传播提升能力 | 同类任务第 N 次比第 1 次效果好 |
| **H4** | 整体效果在垂直场景可接近 LLM | 业务场景效果不差于纯 LLM |

---

## 1. Wenshi 总体架构

### 1.1 架构全景

```
┌──────────────────────────────────────────────────────────────────────────┐
│                                                                          │
│                    Wenshi（格物知行）新一代大模型架构                      │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │  第三层 · 学习层（经验进化）                                         │  │
│  │  ┌──────────────┐ ┌────────────┐ ┌──────────┐ ┌────────────────┐  │  │
│  │  │ Experience   │ │ Reflection │ │ Skill    │ │ Knowledge      │  │  │
│  │  │ Replay       │ │ & Critique │ │ Evolution│ │ Distillation   │  │  │
│  │  │ 经验回放      │ │ 反思复盘   │ │ 技能演化  │ │ 知识蒸馏       │  │  │
│  │  └──────┬───────┘ └─────┬──────┘ └────┬─────┘ └───────┬────────┘  │  │
│  └─────────┼───────────────┼─────────────┼───────────────┼────────────┘  │
│            │ 写经验/能力    │ 读策略       │ 沉淀 Skill    │ 触发蒸馏     │
│            ▼               ▼             ▼               ▼              │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │  第二层 · 推理层（思维编排）         neuro-symbolic 混合推理          │  │
│  │  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌───────┐ │  │
│  │  │Planner │ │Memory  │ │Solver  │ │Critic  │ │Symbolic│ │Language│ │  │
│  │  │任务规划│ │Router  │ │求解器  │ │批判器  │ │推理器  │ │接口    │ │  │
│  │  │(MCTS/  │ │记忆路由│ │(算法+  │ │(接地   │ │(规则/  │ │(LLM/  │ │  │
│  │  │ ToT)   │ │        │ │ 搜索)  │ │ 验证)  │ │ 逻辑)  │ │ 小模型)│ │  │
│  │  └────────┘ └────────┘ └────────┘ └────────┘ └────────┘ └───────┘ │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│            │ 检索请求                                    │ 读能力        │
│            ▼                                            ▼              │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │  第一层 · 知识层（结构化记忆）                                        │  │
│  │  ┌────────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────────┐    │  │
│  │  │ Semantic   │ │ Episodic │ │Procedural│ │ Parametric       │    │  │
│  │  │ 语义记忆    │ │ 情景记忆  │ │程序性记忆│ │ 参数化记忆       │    │  │
│  │  │ 知识图谱    │ │ 时序事件  │ │工具/SOP  │ │ 用户偏好         │    │  │
│  │  │ + 向量索引  │ │ + 向量    │ │ + Skill  │ │ + 人格配置       │    │  │
│  │  └────────────┘ └──────────┘ └──────────┘ └──────────────────┘    │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

### 1.2 数据流（用户任务进入后的完整处理）

```
用户输入 "帮我查一下上周的销售数据并做一个汇总分析"
  │
  ▼
┌─ 语言接口 ──────────────────────────────────────────────┐
│  Language Interface                                      │
│  - 接收自然语言输入                                       │
│  - 调用 LLM/小模型做意图识别 + 实体抽取                     │
│  - 输出：结构化意图 {action: "查询+分析", target: "销售数据", │
│           time: "上周", format: "汇总"}                    │
└─────────────────────────────────────────────────────────┘
  │
  ▼
┌─ 推理层 · Planner ──────────────────────────────────────┐
│  - 任务分解：                                             │
│    ① 查询上周销售数据                                     │
│    ② 数据清洗与汇总                                       │
│    ③ 生成分析报告                                         │
│  - 依赖分析：① → ② → ③                                   │
│  - 策略选择：查经验库是否有类似任务                         │
└─────────────────────────────────────────────────────────┘
  │                    │                    │
  ▼                    ▼                    ▼
┌─ ① 执行 ──────┐  ┌─ ② 执行 ──────┐  ┌─ ③ 执行 ──────┐
│ MemoryRouter  │  │ MemoryRouter  │  │ MemoryRouter  │
│ → 知识层检索   │  │ → 知识层检索   │  │ → 经验库检索   │
│ 销售数据表结构  │  │ 清洗规则      │  │ 分析报告模板   │
│               │  │               │  │               │
│ Symbolic      │  │ Symbolic      │  │ Language      │
│ Solver        │  │ Solver        │  │ Interface     │
│ → SQL 生成    │  │ → 数据聚合    │  │ → 报告生成    │
│ → 工具调用    │  │ → 计算汇总    │  │ → 格式化输出  │
│               │  │               │  │               │
│ Critic        │  │ Critic        │  │ Critic        │
│ → SQL 执行验证 │  │ → 数值校验    │  │ → 格式校验    │
└───────────────┘  └───────────────┘  └───────────────┘
  │                    │                    │
  └────────────────────┼────────────────────┘
                       ▼
┌─ 输出组装 ──────────────────────────────────────────────┐
│  - 汇总三个子任务结果                                      │
│  - 生成最终回答                                           │
│  - 推理轨迹完整记录                                       │
└─────────────────────────────────────────────────────────┘
  │
  ▼
┌─ 学习层 ────────────────────────────────────────────────┐
│  - ExperienceExtractor：抽取本次经验                      │
│    {scenario: "销售数据分析", strategy: "SQL+聚合+模板",   │
│     outcome: "SUCCESS", score: 0.92}                     │
│  - 写入经验库 + 向量索引                                   │
│  - 下次遇到类似任务 → 直接复用策略（无需 LLM 重推理）       │
└─────────────────────────────────────────────────────────┘
```

### 1.3 与现有系统的关系

现有 `gewu-platform` 的 Agent 系统是 Wenshi 的**宿主环境**和**对比基线**：

| 现有系统 | 在 Wenshi 中的角色 |
|---------|-------------------|
| `AgentExecutionEngine` | 被替代的目标（逐步替换其推理逻辑） |
| `LlmClientFactory` | 降级为"语言接口"（仅处理自然语言 I/O） |
| `SessionContextService` | 升级为"记忆路由" |
| `ToolExecutionService` | 升级为"程序性记忆 + Skill 调度" |
| `AiChatController` | 保持不变（Wenshi 对前端透明） |

---

## 2. 第一层 · 知识层（结构化记忆）

### 2.1 设计理念

> **知识不应该锁在权重矩阵里**。
> 传统 LLM 的 175B 参数本质上是一种极低效的知识压缩 —— 不可读、不可写、不可增量。
> Wenshi 的知识层用结构化数据存储知识，实现：
> - **可读**：人类可以直接查看和修改
> - **可写**：新知识实时写入，无需重训练
> - **可解释**：每个决策都能追溯到具体知识条目
> - **可审计**：知识变更全程有日志

### 2.2 四类记忆

| 记忆类型 | 存储内容 | 数据结构 | 检索方式 |
|---------|---------|---------|---------|
| **语义记忆** | 概念、事实、业务规则、实体关系 | 知识图谱（实体-关系）+ 向量索引 | 图谱遍历 + 语义检索 |
| **情景记忆** | 历史对话、任务执行过程、事件序列 | 时序事件库 + 向量索引 | 时间范围 + 语义检索 |
| **程序性记忆** | 工具定义、SOP、可复用 Skill | 结构化文档 + 代码 | 名称匹配 + 语义检索 |
| **参数化记忆** | 用户偏好、人格设定、上下文参数 | KV 配置 | Key 精确匹配 |

### 2.3 知识写入管线

```
数据来源
  ├─ 人工录入（管理员配置业务知识）
  ├─ 文档导入（PDF/Word/Markdown → 知识抽取）
  ├─ 对话沉淀（每次任务后自动抽取）
  ├─ 工具执行结果（自动存入情景记忆）
  └─ 外部 API（数据库 schema、接口文档等）
        │
        ▼
  ┌─ 知识抽取 ──────────────────┐
  │  - 文本分块                   │
  │  - 实体/关系抽取（LLM 辅助）   │
  │  - 向量化（Embedding）        │
  │  - 质量评分（去重/去噪）       │
  └─────────────────────────────┘
        │
        ▼
  ┌─ 知识存储 ──────────────────┐
  │  - 图谱：实体/关系写入 Neo4j  │
  │  - 向量：embedding 写入 pgvector│
  │  - 原文：content 写入知识库    │
  │  - 元数据：来源/置信度/时间    │
  └─────────────────────────────┘
```

### 2.4 数据模型

#### 语义记忆 · 知识图谱
```cypher
(:Entity {id, name, type, tenant_id, embedding[]})
(:Fact   {id, statement, source, confidence, embedding[]})
(:Entity)-[:RELATED_TO {relation, weight}]->(:Entity)
(:Fact)-[:ABOUT]->(:Entity)
```

#### 向量索引（pgvector）
```sql
CREATE TABLE knowledge_fragment (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    memory_type   VARCHAR(20) NOT NULL,  -- SEMANTIC/EPISODIC/PROCEDURAL
    content       TEXT NOT NULL,
    ref_id        VARCHAR(64),           -- 关联图谱节点/事件/Skill id
    source        VARCHAR(32),           -- MANUAL/IMPORT/DIALOGUE/TOOL/API
    confidence    NUMERIC(3,2) DEFAULT 1.0,
    embedding     vector(1536),
    metadata      JSONB,
    created_at    TIMESTAMP,
    updated_at    TIMESTAMP
);
CREATE INDEX idx_kf_embedding ON knowledge_fragment
    USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_kf_tenant_type ON knowledge_fragment(tenant_id, memory_type);
```

#### 情景记忆 · 事件库
```sql
CREATE TABLE episodic_event (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL,
    user_id       BIGINT NOT NULL,
    session_id    VARCHAR(64),
    event_type    VARCHAR(32),           -- TASK_START/TOOL_CALL/RESULT/ERROR
    content       TEXT,
    embedding     vector(1536),
    metadata      JSONB,
    created_at    TIMESTAMP
);
```

#### 程序性记忆 · Skill 库
```sql
CREATE TABLE skill (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL,
    name          VARCHAR(128) NOT NULL,
    description   TEXT,
    definition    JSONB NOT NULL,        -- 工具组合/SOP/参数模板
    skill_level   INT DEFAULT 1,         -- 1-5 成熟度
    usage_count   INT DEFAULT 0,
    success_rate  NUMERIC(5,2),
    embedding     vector(1536),
    learned_from  VARCHAR(64),           -- 来源经验 id
    created_at    TIMESTAMP,
    updated_at    TIMESTAMP
);
```

### 2.5 检索接口

```java
public interface KnowledgeRetriever {
    // 混合检索：向量召回 + 图谱扩展 + 元数据过滤
    RetrievalResult retrieve(RetrievalQuery query);

    // 按记忆类型定向检索
    List<Fragment> retrieveByType(MemoryType type, String query, int topK);

    // 图谱关系展开
    GraphSubtree expandGraph(String entityId, int hops);

    // 时序检索：时间范围 + 语义
    List<EpisodicEvent> retrieveEpisodic(String userId, TimeRange range, String semantic);
}

@Data
class RetrievalQuery {
    private Long tenantId;
    private Long userId;
    private String query;
    private List<MemoryType> memoryTypes;
    private int topK;
    private Double threshold;
    private TimeRange timeRange;
    private Map<String, Object> filters;
}
```

---

## 3. 第二层 · 推理层（Neuro-Symbolic 混合推理）

### 3.1 设计理念

> **推理不应该是矩阵乘法，而应该是可解释的思维过程**。
> Wenshi 的推理层是 neuro-symbolic 混合架构：
> - **符号推理**（算法）：规划、搜索、规则、逻辑 —— 确定性强、可解释
> - **神经语言**（LLM/小模型）：自然语言理解/生成 —— 仅在语言接口层使用
> - **核心目标**：最小化对 LLM 的依赖，让算法承担 80%+ 的推理工作

### 3.2 推理引擎架构

```
┌──────────────────────────────────────────────────────────────┐
│                    Wenshi Reasoning Engine                    │
│                                                              │
│  ┌─ 入口 ──────────────────────────────────────────────────┐ │
│  │  ReasoningRequest {task, context, constraints}           │ │
│  └────────────────────────────────────────────────────────┘ │
│                          │                                   │
│                          ▼                                   │
│  ┌─ Planner ──────────────────────────────────────────────┐ │
│  │  ① 经验匹配：经验库中是否有相似任务？                     │ │
│  │     → 有：直接复用策略（跳过后续推理）                     │ │
│  │     → 无：进入规划                                       │ │
│  │  ② 任务分解：将复杂任务拆成子目标 DAG                      │ │
│  │  ③ 策略选择：为每个子目标选择推理策略                      │ │
│  │     - 结构化问题 → SymbolicSolver（规则/搜索）            │ │
│  │     - 语言问题 → LanguageInterface（LLM）                │ │
│  │     - 混合问题 → 先符号后语言                             │ │
│  └────────────────────────────────────────────────────────┘ │
│                          │                                   │
│                          ▼                                   │
│  ┌─ Solver 调度 ──────────────────────────────────────────┐ │
│  │                                                          │ │
│  │  ┌─ SymbolicSolver ──────┐  ┌─ LanguageInterface ───┐  │ │
│  │  │ - 规则引擎（Drools）    │  │ - LLM（DeepSeek）      │  │ │
│  │  │ - 搜索算法（MCTS）      │  │ - 小模型（Qwen）       │  │ │
│  │  │ - 规划器（PDDL/HTN）    │  │ - 模板填充             │  │ │
│  │  │ - 逻辑推理（Prolog）    │  │ - 结构化输出           │  │ │
│  │  │ - SQL 生成             │  │                        │  │ │
│  │  │ - 数据聚合/计算         │  │  仅在以下场景使用：      │  │ │
│  │  │                        │  │  - 自然语言理解          │  │ │
│  │  │  优先使用（80%+任务）    │  │  - 自然语言生成          │  │ │
│  │  │                        │  │  - 模糊语义匹配          │  │ │
│  │  └────────────────────────┘  └────────────────────────┘  │ │
│  └────────────────────────────────────────────────────────┘ │
│                          │                                   │
│                          ▼                                   │
│  ┌─ Critic ───────────────────────────────────────────────┐ │
│  │  ① 结果验证：输出是否符合预期？                           │ │
│  │  ② 接地检查：用外部信号验证（测试/执行/规则）              │ │
│  │  ③ 失败反思：不通过 → 修正策略 → 重试                    │ │
│  └────────────────────────────────────────────────────────┘ │
│                          │                                   │
│                          ▼                                   │
│  ┌─ 出口 ──────────────────────────────────────────────────┐ │
│  │  ReasoningResult {answer, plan, trace, tokenStats}      │ │
│  └────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

### 3.3 推理策略选择

| 任务类型 | 首选策略 | 备选策略 | 触发条件 |
|---------|---------|---------|---------|
| 数据查询 | SymbolicSolver → SQL 生成 | LanguageInterface | 结构化数据源 |
| 数值计算 | SymbolicSolver → 直接计算 | — | 数学运算 |
| 流程执行 | SymbolicSolver → SOP 匹配 | — | 有现成 SOP |
| 知识问答 | 知识层检索 → 直接回答 | LanguageInterface | 知识库有答案 |
| 文本生成 | LanguageInterface | 模板填充 | 需要自然语言输出 |
| 模糊推理 | LanguageInterface → 结构化 | SymbolicSolver | 语义不明确 |
| 复杂规划 | Planner → MCTS/ToT | LanguageInterface | 多步骤依赖 |

### 3.4 经验复用机制（核心差异化）

这是 Wenshi 区别于传统 LLM 的关键 —— **不需要每次都从头推理**：

```
传统 LLM：
  每次请求 → 从头推理（175B 参数前向传播）
  成本：O(参数数)，每次相同

Wenshi：
  第 1 次请求 → 完整推理 → 沉淀经验
  第 2 次类似请求 → 经验匹配 → 直接复用策略
  成本：O(经验检索) << O(LLM 推理)

  随着使用时间增长：
  - 经验库越来越丰富
  - 需要 LLM 的场景越来越少
  - 成本越来越低，效果越来越好
```

### 3.5 接口契约

```java
public interface WenshiReasoningEngine {
    // 同步推理
    ReasoningResult reason(ReasoningRequest request);

    // 流式推理
    Flux<ReasoningChunk> reasonStream(ReasoningRequest request);
}

@Data
class ReasoningRequest {
    private Long tenantId;
    private Long userId;
    private String sessionId;
    private String task;                    // 用户任务（自然语言）
    private StructuredIntent intent;        // 语言接口解析后的结构化意图
    private ReasoningConstraints constraints; // 预算/策略/超时
}

@Data
class ReasoningResult {
    private String answer;                  // 最终回答（自然语言）
    private Object structuredOutput;        // 结构化输出（数据/报告）
    private PlanTree plan;                  // 计划树（可追溯）
    private List<TraceStep> trace;          // 推理轨迹（可审计）
    private List<Fragment> usedKnowledge;   // 使用的知识条目
    private Experience reusedExperience;     // 复用的经验（如有）
    private TokenStatistics tokenStats;      // token 消耗统计
    private long reasoningTimeMs;           // 推理耗时
}
```

---

## 4. 第三层 · 学习层（经验进化）

### 4.1 设计理念

> **学习不应该需要 GPU 集群**。
> 传统 LLM 的学习 = 反向传播 + 海量数据 + 巨大算力。
> Wenshi 的学习 = 经验积累 + 技能沉淀 + 知识蒸馏。
> 每次任务都是一次学习机会，系统越用越聪明。

### 4.2 学习闭环

```
任务完成
  │
  ▼
┌─ 经验抽取 ──────────────────────────────────────────────┐
│  ExperienceExtractor（LLM 辅助）                          │
│  输入：完整推理轨迹 + 结果                                  │
│  输出：{scenario, strategy, outcome, score, lesson}       │
└─────────────────────────────────────────────────────────┘
  │
  ├─ score > 0.9 且同类经验 ≥ 3 条 ─→ 技能封装 ─→ Skill 库
  │
  ├─ score < 0.6 ─→ 反思复盘 ─→ 修正策略 ─→ 更新经验
  │
  └─ 正常 ─→ 经验库写入（+ 向量索引）
        │
        ▼
  下次类似任务 → 经验匹配 → 直接复用
```

### 4.3 学习效果曲线（预期）

```
效果 ↑
     │                              ╱ Wenshi（经验积累）
     │                            ╱
     │                          ╱
     │                        ╱
     │                      ╱
     │────────────────────╱────────── LLM（恒定不变）
     │
     └──────────────────────────────→ 使用次数/时间
     1    5    10   20   50   100
```

LLM 的效果是恒定的（不训练就不变），Wenshi 的效果随使用次数增长。

### 4.4 数据模型

```sql
CREATE TABLE experience (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL,
    scenario_hash VARCHAR(64) NOT NULL,   -- 场景指纹（相似匹配）
    scenario      TEXT NOT NULL,          -- 任务场景描述
    strategy      TEXT NOT NULL,          -- 采用的策略/计划
    outcome       VARCHAR(20),           -- SUCCESS/PARTIAL/FAIL
    score         NUMERIC(3,2),          -- 评估得分 0-1
    lesson        TEXT,                  -- 学到的经验
    embedding     vector(1536),          -- 场景语义向量
    source_task   VARCHAR(64),           -- 来源任务 id
    hit_count     INT DEFAULT 0,         -- 被复用次数
    created_at    TIMESTAMP,
    updated_at    TIMESTAMP
);
CREATE INDEX idx_exp_hash ON experience(scenario_hash);
CREATE INDEX idx_exp_embedding ON experience USING hnsw (embedding vector_cosine_ops);
```

### 4.5 接口契约

```java
public interface WenshiLearningService {
    // 经验吸收：任务完成后调用
    Experience absorb(Experience experience);

    // 相似经验召回：推理层调用
    List<Experience> recallSimilar(String scenario, int topK);

    // 反思复盘：失败时调用
    ReflectionReport reflect(Long experienceId);

    // 技能演化：定时任务
    List<Skill> evolveCapabilities();

    // 效果统计：监控用
    LearningMetrics getMetrics();
}
```

---

## 5. 语言接口（LLM 的角色）

### 5.1 定位

> LLM 在 Wenshi 中**不是推理引擎，而是语言接口**。
> 它只做两件事：
> 1. **理解**：把自然语言输入转成结构化意图
> 2. **表达**：把结构化输出转成自然语言回答

### 5.2 使用场景

| 场景 | 是否用 LLM | 说明 |
|------|-----------|------|
| 意图识别 | ✅ 是 | "查上周销售数据" → {action: "查询", target: "销售数据", time: "上周"} |
| 实体抽取 | ✅ 是 | 从文本中提取时间/地点/指标等实体 |
| 回答生成 | ✅ 是 | 把结构化结果转成自然语言 |
| 任务规划 | ❌ 否 | 用 Planner 算法 |
| 数据查询 | ❌ 否 | 用 SQL 生成器 |
| 知识检索 | ❌ 否 | 用向量检索 |
| 经验匹配 | ❌ 否 | 用经验库语义检索 |
| 结果验证 | ❌ 否 | 用外部验证器 |

### 5.3 最小化依赖策略

```
阶段 A（PoC）：LLM 做意图识别 + 回答生成（2 个调用/请求）
阶段 B：意图识别 → 规则引擎 + 小模型（减少 LLM 调用）
阶段 C：回答生成 → 模板填充 + 小模型（进一步减少）
阶段 D：仅保留模糊语义匹配用 LLM（目标：< 0.5 次调用/请求）
```

---

## 6. 对比实验设计

### 6.1 基线模型

| 基线 | 说明 | 调用方式 |
|------|------|---------|
| DeepSeek（主力） | 当前生产模型 | 直接 API 调用 |
| Qwen | 备选模型 | 直接 API 调用 |
| GPT-4/5 | 商业模型 | 直接 API 调用 |
| DeepSeek + 现有 Agent | 当前 gewu-platform | 现有 Agent 编排 |

### 6.2 评估维度

| 维度 | 指标 | 测量方式 |
|------|------|---------|
| **准确性** | 任务完成率 | 人工标注 + 自动验证 |
| **延迟** | P50/P95/P99 响应时间 | 端到端打点 |
| **成本** | 每请求 LLM token 消耗 | token 统计 |
| **可解释性** | 推理轨迹完整度 | 轨迹步骤覆盖率 |
| **增量学习** | 同类任务第 1 次 vs 第 10 次效果 | A/B 对比 |
| **鲁棒性** | LLM 不可用时的降级效果 | 模拟故障 |

### 6.3 测试场景

| 场景 | 类型 | 难度 |
|------|------|------|
| 知识问答 | 检索+回答 | 简单 |
| 数据查询 | 结构化查询 | 中等 |
| 数据分析 | 查询+计算+报告 | 复杂 |
| 流程执行 | 多步骤 SOP | 中等 |
| 异常处理 | 错误恢复 | 困难 |
| 多轮对话 | 上下文理解 | 中等 |

---

## 7. PoC 执行计划（3 人 × 3 个月）

### 7.1 人员分工

| 角色 | 人数 | 职责 | 阶段重点 |
|------|------|------|---------|
| **架构师/算法** | 1 人 | 推理引擎设计、算法实现、效果评估 | 推理层 + 评估 |
| **后端开发** | 1 人 | 知识层 + 学习层 + 数据管线 | 知识层 + 学习层 |
| **全栈开发** | 1 人 | 前端可视化 + 业务对接 + 测试 | 可视化 + 测试 |

### 7.2 月度里程碑

#### 第 1 个月：知识层 + 推理层骨架

| 周 | 任务 | 交付 | 负责人 |
|----|------|------|--------|
| W1 | pgvector 基础设施 + 知识写入管线 | 知识可写入+检索 | 后端 |
| W1 | 推理引擎骨架（Planner + Solver 接口） | 推理引擎可运行 | 架构师 |
| W2 | MemoryRetriever（向量+元数据） | 检索命中率 > 85% | 后端 |
| W2 | SymbolicSolver（规则+SQL生成） | 结构化任务可解 | 架构师 |
| W3 | LanguageInterface（LLM 调用） | 语言 I/O 可用 | 全栈 |
| W3 | Planner（任务分解+策略选择） | 任务可分解+路由 | 架构师 |
| W4 | 集成测试 + H1 验证 | 知识召回率报告 | 全员 |

#### 第 2 个月：学习层 + 业务对接

| 周 | 任务 | 交付 | 负责人 |
|----|------|------|--------|
| W5 | 经验库 + ExperienceExtractor | 经验可抽取+存储 | 后端 |
| W5 | Critic（接地验证） | 结果可验证 | 架构师 |
| W6 | 技能库 + CapabilityEvolution | 技能可沉淀 | 后端 |
| W6 | 经验复用机制 | 类似任务可复用 | 架构师 |
| W7 | 业务场景对接（现有 Agent） | 可在业务场景运行 | 全栈 |
| W7 | 推理轨迹可视化 | 前端可查看推理过程 | 全栈 |
| W8 | H2+H3 验证 | 推理可追溯+经验有效 | 全员 |

#### 第 3 个月：评估 + 论文/专利

| 周 | 任务 | 交付 | 负责人 |
|----|------|------|--------|
| W9-W10 | 对比实验（4 个基线 × 6 个场景） | 实验数据 | 全员 |
| W11 | H4 验证 + 数据分析 | 效果对比报告 | 架构师 |
| W11 | 成本分析（token/延迟/存储） | 成本对比报告 | 后端 |
| W12 | 论文撰写 + 专利申请 | 论文/专利初稿 | 架构师 |
| W12 | PoC 报告 + 后续路线图 | 总结报告 | 全员 |

### 7.3 交付物

| 交付物 | 形式 | 时间 |
|--------|------|------|
| PoC 系统 | 可演示原型（对接现有业务） | W8 |
| 对比实验报告 | 4 基线 × 6 场景数据 | W11 |
| 架构验证报告 | H1-H4 假设验证结论 | W11 |
| 专利申请 | 1 项发明专利 | W12 |
| 论文 | 1 篇（顶会/期刊投稿） | W12 |
| 后续路线图 | 基于验证结论的决策 | W12 |

---

## 8. 风险与诚实面对

### 8.1 验证失败的标准

| 假设 | 失败标准 | 失败后的价值 |
|------|---------|-------------|
| H1 | 结构化知识无法被推理层有效使用 | 至少验证了"什么不行" |
| H2 | 算法推理效果远差于 LLM | 产出 neuro-symbolic 实验数据 |
| H3 | 经验积累对效果无显著提升 | 产出"经验学习是否有效"的结论 |
| H4 | 混合推理效果不如纯 LLM | 产出架构对比论文，指导后续方向 |

### 8.2 核心风险

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| 符号推理覆盖度不够 | 高 | 高 | LLM 兜底，逐步减少 |
| 知识抽取质量差 | 中 | 高 | 人工+自动混合管线 |
| 经验复用匹配不准 | 中 | 中 | 多策略融合匹配 |
| 效果不如纯 LLM | 中 | 中 | 产出"何时用 Wenshi 更有效"的结论 |
| 3 个月时间不够 | 中 | 低 | 优先验证 H1+H2 |

---

## 9. 待决策项

| # | 问题 | 选项 | 紧迫度 |
|---|------|------|--------|
| Q1 | 符号推理引擎选型 | A) 自研轻量 B) Drools 规则引擎 C) 自研+MCTS | 高 |
| Q2 | embedding 模型选型 | A) 复用 LLM B) BGE 小模型 C) 商业 API | 高 |
| Q3 | 知识抽取方式 | A) LLM 抽取 B) 规则抽取 C) 混合 | 中 |
| Q4 | 经验匹配算法 | A) 纯向量 B) 向量+规则 C) 图谱+向量 | 中 |
| Q5 | 论文投稿目标 | A) NeurIPS/ICML B) ACL/EMNLP C) 国内顶刊 | 低 |

---

## 10. 与 v1.1 的继承关系

| v1.1 内容 | v2.0 处理 |
|-----------|----------|
| 三层架构框架 | ✅ 继承，重新定位为"模型"而非"操作系统" |
| 四类记忆分区 | ✅ 继承 |
| pgvector + Rerank + 压缩 | ✅ 继承 |
| Plan-and-Solve + 并行 | ✅ 继承，扩展为完整 neuro-symbolic |
| 经验回放 + 技能演化 | ✅ 继承 |
| Token 成本优化 | ✅ 继承 |
| LLM 降级策略 | ⚠️ 降级为"语言接口可用性保障" |
| 多租户安全设计 | ✅ 继承 |
| 防 token 爆炸纪律 | ✅ 继承 |

---

> **一句话总结**：Wenshi 不是给 LLM 加外挂，而是验证一种新的模型架构 ——
> 知识存在结构化记忆中（白盒），推理用算法编排（可控），学习靠经验积累（越用越聪明）。
> LLM 只是语言接口，不是核心。3 个月 PoC 验证四个核心假设，产出原型+论文+专利。
