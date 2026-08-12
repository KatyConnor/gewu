# Wenshi（格物知行）—— 企业级 LLM 增强架构

> 版本：v3.0  ·  日期：2026-07-16  ·  状态：已定型 ·  作者：架构组
>
> 定位：企业级 LLM 增强架构标准。任何已部署 LLM 的企业都可以接入，
> 补齐大模型的长期记忆、业务知识、经验积累、可解释性等短板。

---

## 0. 核心定位

### 0.1 一句话

> **给企业已部署的大模型装上"记忆+知识+经验"三层增强，让 LLM 越用越聪明。**

### 0.2 架构定位

```
传统：LLM = 全部
Wenshi：LLM（底座）+ 增强层（记忆+知识+经验）= 越用越聪明的智能体
```

### 0.3 解决什么问题

| 大模型固有短板 | Wenshi 解法 |
|-------------|------------|
| 无长期记忆（只有 context window） | 知识层：无限结构化记忆 |
| 无业务知识（只知道通用知识） | 知识层：企业专属知识库 |
| 无经验积累（每次都是新的） | 学习层：经验回放 + 技能演化 |
| 推理不可解释（黑盒） | 推理层：可追溯的推理轨迹 |
| 幻觉问题 | 知识层校验 + Critic 接地验证 |
| token 成本高 | 经验复用 + 按需注入，减少 LLM 调用 |

### 0.4 设计原则

| 原则 | 含义 |
|------|------|
| **模型无关** | 不绑定任何 LLM，通过适配层对接企业已有模型 |
| **存储无关** | 不绑定具体数据库，支持企业已有的存储基础设施 |
| **可插拔** | 每个层级可独立替换/升级 |
| **可配置** | 企业按需选择启用哪些能力 |
| **最小侵入** | 不改变企业现有模型部署和业务流程 |

---

## 1. 总体架构

### 1.1 架构全景

```
┌──────────────────────────────────────────────────────────────────────────┐
│                                                                          │
│                Wenshi（格物知行）企业级 LLM 增强架构                       │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │  第三层 · 学习层（经验进化）                                         │  │
│  │  ┌──────────────┐ ┌────────────┐ ┌──────────┐ ┌────────────────┐  │  │
│  │  │ 经验抽取      │ │ 质量评估   │ │ 技能演化  │ │ 反思复盘       │  │  │
│  │  │ 规则+LLM     │ │ 规则+LLM   │ │ 规则+LLM │ │ 规则+LLM       │  │  │
│  │  └──────────────┘ └────────────┘ └──────────┘ └────────────────┘  │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │  第二层 · 推理层（思维编排）                                         │  │
│  │  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐          │  │
│  │  │Planner │ │Memory  │ │Solver  │ │Critic  │ │效果度量 │          │  │
│  │  │模板+LLM│ │Router  │ │经验优先│ │接地验证│ │在线+离线│          │  │
│  │  │混合策略│ │规则优先│ │四级路由│ │三级递进│ │混合度量│          │  │
│  │  └────────┘ └────────┘ └────────┘ └────────┘ └────────┘          │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │  第一层 · 知识层（结构化记忆）                                        │  │
│  │  ┌────────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────────┐    │  │
│  │  │ 语义记忆    │ │ 情景记忆  │ │程序性记忆│ │ 参数化记忆       │    │  │
│  │  │ 图谱+向量   │ │ 时序+向量 │ │分类存储  │ │ KV+DB 双写      │    │  │
│  │  └────────────┘ └──────────┘ └──────────┘ └──────────────────┘    │  │
│  │                                                                    │  │
│  │  ┌────────────────────────────────────────────────────────────┐   │  │
│  │  │ 记忆路由：规则优先 + LLM 兜底  ·  注入方式：按需注入         │   │  │
│  │  └────────────────────────────────────────────────────────────┘   │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │  适配层（屏蔽底层差异，企业按需选择）                                 │  │
│  │  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐      │  │
│  │  │ LlmAdapter │ │EmbedAdapter│ │VectorStore │ │ TenantIso  │      │  │
│  │  │ 模型适配   │ │嵌入适配    │ │向量库适配  │ │租户隔离    │      │  │
│  │  └────────────┘ └────────────┘ └────────────┘ └────────────┘      │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │  基础设施层（企业已有，不绑定任何具体产品）                            │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐              │  │
│  │  │ 企业LLM  │ │ 向量库   │ │ 图谱库   │ │ KV/DB    │              │  │
│  │  │DeepSeek/ │ │pgvector/ │ │Neo4j/    │ │Redis/PG  │              │  │
│  │  │Qwen/GLM  │ │Milvus等  │ │Kuzu等    │ │MySQL等   │              │  │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘              │  │
│  └────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

### 1.2 数据流

```
用户输入自然语言
  │
  ▼
┌─ Language Interface（LLM）───────────────────────────────────────┐
│  自然语言 → 结构化意图 {action, target, params, constraints}      │
└─────────────────────────────────────────────────────────────────┘
  │
  ▼
┌─ Planner ────────────────────────────────────────────────────────┐
│  ① 模板匹配：有匹配 → 直接返回预定义计划                          │
│  ② LLM 分解：无匹配 → LLM 生成计划树                             │
│  输出：PlanTree（子目标 DAG）                                     │
└─────────────────────────────────────────────────────────────────┘
  │
  ▼ （每个子目标）
┌─ SolverRouter ───────────────────────────────────────────────────┐
│  ① 经验库匹配 → 命中 → 直接复用策略（零 LLM 成本）               │
│  ② 知识库检索 → 命中 → 直接回答（零 LLM 成本）                   │
│  ③ 规则匹配 → 命中 → 调工具执行（低 LLM 成本）                   │
│  ④ LLM 推理 → 兜底（唯一需要 LLM 全量推理的路径）                │
└─────────────────────────────────────────────────────────────────┘
  │
  ▼
┌─ Critic ─────────────────────────────────────────────────────────┐
│  ① 外部验证：执行结果可验证？→ 直接判定                           │
│  ② 规则校验：格式/范围/类型检查                                   │
│  ③ LLM 自检：以上都不行 → LLM 评判                               │
│  不通过 → 修正 → 重试（限 3 轮）                                 │
└─────────────────────────────────────────────────────────────────┘
  │
  ▼
┌─ 输出 ───────────────────────────────────────────────────────────┐
│  结构化结果 → Language Interface（LLM）→ 自然语言回答             │
└─────────────────────────────────────────────────────────────────┘
  │
  ▼
┌─ 学习层 ─────────────────────────────────────────────────────────┐
│  规则抽取经验 → 质量评估 → 入库/封装技能/反思复盘                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. 适配层

### 2.1 设计原则

> **标准接口 + 多适配器可配，企业零改造接入。**

所有底层能力通过标准接口对接，企业根据自身环境选择适配器。

### 2.2 LlmAdapter（LLM 适配）

```java
public interface LlmAdapter {
    LlmResponse chat(LlmRequest request);
    Flux<LlmResponse> chatStream(LlmRequest request);
    float[] embed(String text);
    LlmCapabilities getCapabilities();
}

@Data
class LlmCapabilities {
    private boolean supportsTools;
    private boolean supportsStreaming;
    private int maxContextTokens;
    private Set<String> supportedModels;
}
```

| 适配器 | 适用场景 |
|--------|---------|
| DeepSeekAdapter | 企业部署了 DeepSeek |
| QwenAdapter | 企业部署了 Qwen |
| OpenAIAdapter | 使用 OpenAI API |
| CustomAdapter | 企业自研 LLM |

### 2.3 EmbeddingAdapter（嵌入适配）

```java
public interface EmbeddingAdapter {
    float[] embed(String text);
    float[][] embedBatch(List<String> texts);
    int getDimension();
}
```

| 适配器 | 适用场景 | 维度 |
|--------|---------|------|
| BgeSmallAdapter | 默认推荐，CPU 即可 | 384 |
| LlmNativeAdapter | 快速验证，零新依赖 | 1024-1536 |
| OpenAiAdapter | 无本地模型 | 1536 |
| CustomAdapter | 企业自研 embedding | 自定义 |

### 2.4 VectorStore（向量库适配）

```java
public interface VectorStore {
    void upsert(List<VectorFragment> fragments);
    List<VectorFragment> search(float[] query, int topK, Map<String, Object> filters);
    void delete(List<String> ids);
}
```

| 适配器 | 适用场景 |
|--------|---------|
| PgvectorAdapter | 已有 PostgreSQL |
| MilvusAdapter | 大规模向量 |
| QdrantAdapter | 需要高级过滤 |
| CustomAdapter | 企业自研向量库 |

### 2.5 TenantIsolation（租户隔离）

```java
public interface TenantIsolation {
    <T> T enforceFilter(T query);
    void auditLog(Operation operation, Long userId, String resource);
    boolean validateAccess(Long userId, String resource);
}
```

| 适配器 | 隔离级别 | 适用场景 |
|--------|---------|---------|
| RowLevelIsolation | 逻辑隔离 | 默认，中小规模 |
| SchemaIsolation | 逻辑隔离 | 需要数据导出 |
| DatabaseIsolation | 物理隔离 | 强合规要求 |

---

## 3. 第一层 · 知识层

### 3.1 四类记忆

| 记忆类型 | 存储内容 | 存储结构 | 检索方式 |
|---------|---------|---------|---------|
| **语义记忆** | 概念、事实、规则、实体关系 | 图谱+向量混合 | 精确查询走图谱，模糊查询走向量 |
| **情景记忆** | 历史对话、事件、任务过程 | 时序+向量 | 时间范围+语义双重检索 |
| **程序性记忆** | 工具定义、SOP、Skill | 分类存储统一接口 | 场景匹配+语义检索 |
| **参数化记忆** | 用户偏好、人格设定、配置 | KV+DB 双写 | Key 精确匹配+行为学习 |

### 3.2 语义记忆

#### 存储结构：图谱+向量混合

```
知识写入
  │
  ├─ 实体/关系/属性 → 写入图谱（Neo4j/Kuzu）
  │
  └─ 原文/描述 → embedding → 写入向量库（pgvector）
       └─ 图谱节点 id ↔ 向量 fragment 关联

知识检索
  │
  ├─ 精确查询（"张三入职时间"）→ 图谱遍历
  │
  └─ 模糊查询（"技术岗位"）→ 向量检索 → 图谱展开邻居
```

#### 数据模型

```cypher
// 图谱节点
(:Entity {id, name, type, tenant_id, embedding_ref})
(:Fact {id, statement, source, confidence, embedding_ref})
(:Rule {id, condition, action, priority})

// 图谱关系
(:Entity)-[:RELATED_TO {relation, weight}]->(:Entity)
(:Fact)-[:ABOUT]->(:Entity)
(:Rule)-[:APPLIES_TO]->(:Entity)
```

```sql
-- 向量索引
CREATE TABLE semantic_fragment (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL,
    graph_node_id VARCHAR(64),           -- 关联图谱节点
    content       TEXT NOT NULL,
    embedding     vector(384),
    metadata      JSONB,
    created_at    TIMESTAMP,
    updated_at    TIMESTAMP
);
CREATE INDEX idx_sf_embedding ON semantic_fragment USING hnsw (embedding vector_cosine_ops);
```

### 3.3 情景记忆

#### 存储结构：时序+向量

```sql
CREATE TABLE episodic_event (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL,
    user_id       BIGINT NOT NULL,
    session_id    VARCHAR(64),
    event_type    VARCHAR(32),           -- MESSAGE/TOOL_CALL/TASK_START/TASK_END/ERROR
    content       TEXT NOT NULL,
    embedding     vector(384),
    metadata      JSONB,
    created_at    TIMESTAMP
);
CREATE INDEX idx_ee_tenant_time ON episodic_event(tenant_id, created_at);
CREATE INDEX idx_ee_embedding ON episodic_event USING hnsw (embedding vector_cosine_ops);
```

#### 检索方式

| 查询类型 | 实现 |
|---------|------|
| 时间范围 | WHERE created_at BETWEEN ? AND ? |
| 语义相似 | embedding cosine 检索 |
| 时间+语义 | 时间过滤后向量检索 |
| 用户维度 | WHERE user_id = ? |

### 3.4 程序性记忆

#### 存储结构：分类存储统一接口

```java
public interface ProceduralStore {
    void registerTool(ToolDefinition tool);
    void registerSOP(SOP sop);
    void registerSkill(Skill skill);
    ToolDefinition getTool(String name);
    SOP findSOP(String scenario);
    Skill findSkill(String scenario);
}
```

| 类型 | 存储格式 | 示例 |
|------|---------|------|
| **工具** | JSON Schema | `{name, description, parameters, returns}` |
| **SOP** | 步骤列表 | `[{step, action, params, next_condition}]` |
| **Skill** | 参数化模板 | `{name, params, steps, output_format}` |

```sql
CREATE TABLE procedural_memory (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL,
    type          VARCHAR(16),           -- TOOL/SOP/SKILL
    name          VARCHAR(128) NOT NULL,
    description   TEXT,
    definition    JSONB NOT NULL,        -- 结构化定义
    embedding     vector(384),           -- 用于语义检索
    usage_count   INT DEFAULT 0,
    success_rate  NUMERIC(5,2),
    skill_level   INT DEFAULT 1,
    created_at    TIMESTAMP,
    updated_at    TIMESTAMP
);
```

### 3.5 参数化记忆

#### 存储结构：KV + DB 双写

```java
public interface ParametricStore {
    <T> T get(Long userId, String key, Class<T> type);
    void set(Long userId, String key, Object value);
    UserProfile getProfile(Long userId);
    void updateFromBehavior(Long userId, Behavior behavior);
}
```

```sql
CREATE TABLE parametric_memory (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL,
    user_id       BIGINT NOT NULL,
    key           VARCHAR(128) NOT NULL,
    value         JSONB NOT NULL,
    source        VARCHAR(32),           -- MANUAL/BEHAVIOR_LEARNED
    updated_at    TIMESTAMP,
    UNIQUE(tenant_id, user_id, key)
);
```

#### 行为学习机制

| 观察 | 学习 |
|------|------|
| 用户每次看到图表都点导出 | preferExport = true |
| 用户每次都用中文提问 | language = "zh" |
| 用户经常查销售数据 | defaultDomain = "sales" |
| 用户偏好简短回答 | responseLength = "short" |

### 3.6 记忆路由

#### 策略：规则优先 + LLM 兜底

```java
public interface MemoryRouter {
    RoutingResult route(TaskContext context);
}
```

| 任务类型 | 路由规则 |
|---------|---------|
| 数据查询 | 语义记忆 + 程序性记忆 |
| 数据分析 | 语义记忆 + 情景记忆 + 程序性记忆 |
| 流程执行 | 程序性记忆 + 参数化记忆 |
| 知识问答 | 语义记忆 |
| 不确定 | LLM 决定查哪些记忆 |

#### 注入方式：按需注入

```
第 1 轮：注入记忆摘要（每条记忆只注入前 100 字）
  │
  ├─ LLM 判断摘要足够 → 直接回答
  │
  └─ LLM 需要更多详情 → 请求展开某条记忆
       │
       第 2 轮：注入完整内容
```

---

## 4. 第二层 · 推理层

### 4.1 Planner（任务分解）

#### 策略：混合（先模板后 LLM）

```java
public interface Planner {
    PlanTree plan(String task, TaskContext context);
}
```

```
任务进入
  │
  ▼
① 模板匹配
   - 预定义任务模板库（高频标准任务）
   - 匹配到 → 直接返回预定义计划（零 LLM 成本）
   │
   ├─ 未匹配
   │
   ▼
② LLM 分解
   - 让 LLM 生成计划树
   - 输出：子目标 DAG
```

### 4.2 SolverRouter（策略路由）

#### 策略：经验优先四级递减

```java
public interface SolverRouter {
    Strategy selectStrategy(Subgoal subgoal, TaskContext context);
}

enum Strategy {
    EXPERIENCE_REUSE,   // 经验复用（零 LLM）
    KNOWLEDGE_LOOKUP,   // 知识检索（零 LLM）
    TOOL_EXECUTION,     // 工具执行（低 LLM）
    LLM_REASONING       // LLM 推理（高 LLM）
}
```

```
子目标进入
  │
  ▼
① 经验库匹配 → 命中 → EXPERIENCE_REUSE
  │              └─ 直接用历史策略，零 LLM 成本
  ├─ 未命中
  ▼
② 知识库检索 → 命中 → KNOWLEDGE_LOOKUP
  │              └─ 直接回答，零 LLM 成本
  ├─ 未命中
  ▼
③ 规则匹配 → 命中 → TOOL_EXECUTION
  │           └─ 调工具执行，低 LLM 成本
  ├─ 未命中
  ▼
④ LLM 推理 → LLM_REASONING（兜底）
                 └─ 唯一需要 LLM 全量推理的路径
```

### 4.3 Critic（结果验证）

#### 策略：外部验证优先三级递进

```java
public interface Critic {
    CriticResult evaluate(Solution solution, TaskContext context);
}
```

```
Solver 输出结果
  │
  ▼
① 外部验证
   - SQL 执行成功？
   - API 返回正常？
   - 数值在合理范围？
   ├─ 可判定 → 直接返回结果
   ├─ 无外部验证手段
   ▼
② 规则校验
   - 格式/类型/范围检查
   ├─ 通过 → 返回结果
   ├─ 不通过 → 进入③
   ▼
③ LLM 自检
   - 让 LLM 评判结果合理性
   ├─ 通过 → 返回结果
   └─ 不通过 → 修正 → 重试（限 3 轮）
```

### 4.4 效果度量

#### 策略：混合（在线监控 + 定期离线评估）

| 维度 | 指标 | 采集方式 |
|------|------|---------|
| 准确率 | 任务完成率 | 自动验证 + 人工抽检 |
| 成本 | 每请求 token 消耗 | 实时统计 |
| 延迟 | P50/P95/P99 响应时间 | 端到端打点 |
| 复用率 | 经验命中次数 / 总请求数 | 实时统计 |
| 学习曲线 | 同类任务第 1 次 vs 第 N 次效果 | 离线评估 |

| 阶段 | LLM 依赖度 | 说明 |
|------|-----------|------|
| 冷启动（第 1 次） | ~100% | 无经验，全靠 LLM |
| 成长期（1-10 次） | ~50% | 部分任务有经验 |
| 成熟期（10 次+） | ~20% | 大部分任务不走 LLM |

---

## 5. 第三层 · 学习层

### 5.1 经验抽取

#### 策略：混合（规则为主 + LLM 补充）

```java
public interface ExperienceExtractor {
    Experience extract(ReasoningTrace trace);
}
```

```
任务完成
  │
  ▼
① 规则抽取（零成本）
   - scenario：从 Planner 任务分类提取
   - strategy：从 Solver 策略序列提取
   - outcome：从执行结果提取
   - score：从 Critic 验证结果提取
   │
   ├─ outcome=FAIL 或 score < 0.6
   │
   ▼
② LLM 补充（按需触发）
   - lesson：分析失败原因，总结教训
   - improvement：建议改进策略
```

### 5.2 质量评估

#### 策略：混合（规则初筛 + LLM 精评）

```java
public interface QualityAssessor {
    QualityResult assess(Experience experience);
}
```

```
经验进入
  │
  ▼
① 规则初筛（零成本）
   - 字段完整性 → 不完整 → 拒绝
   - score > 0.6 → 不达标 → 拒绝
   - cosine < 0.9 → 重复 → 合并/跳过
   │
   ├─ 通过初筛
   ▼
② LLM 精评（按需触发）
   - scenario 是否精确？
   - strategy 是否可复用？
   - lesson 是否有价值？
   ├─ 达标 → 入库
   └─ 不达标 → 拒绝
```

### 5.3 技能演化

#### 策略：混合（规则提取 + LLM 补充）

```java
public interface SkillEvolver {
    List<Skill> evolve(List<Experience> experiences);
}
```

| 触发条件 | 阈值 |
|---------|------|
| 同类经验数量 | ≥ 3 条 |
| 平均成功率 | > 85% |
| 场景相似度 | cosine > 0.8 |

```
多条相似经验
  │
  ▼
① 规则提取（零成本）
   - 公共步骤序列
   - 可变参数（时间/类型/范围）
   - 成功模式
   │
   ▼
② LLM 补充（按需触发）
   - Skill 名称和描述
   - 边界条件和注意事项
```

#### Skill 生命周期

```
创建 → 使用 → 评估 → 升级/降级/废弃
                │
                ├─ 成功率 > 90% → skill_level 提升
                ├─ 成功率 < 60% → 降级或废弃
                ├─ 新成功经验 → 更新步骤
                └─ 30 天未使用 → 休眠
```

### 5.4 反思复盘

#### 策略：混合（规则匹配 + LLM 深度分析）

```java
public interface ReflectionAgent {
    ReflectionReport reflect(Long experienceId);
}
```

| 触发条件 | 是否触发 |
|---------|---------|
| outcome = FAIL | ✅ 必须 |
| score < 0.5 | ✅ 必须 |
| score 0.5-0.7 | ⚠️ 可选 |

```
任务失败
  │
  ▼
① 规则匹配（零成本）
   - SQL 语法错误 → 建议检查字段名
   - 超时 → 建议缩小范围
   - 权限不足 → 建议检查权限
   ├─ 匹配到 → 返回改进建议
   ├─ 未匹配
   ▼
② LLM 深度分析（有成本）
   - 分析完整推理轨迹
   - 定位根因
   - 生成改进策略
   - 更新规则库（自增长）
```

### 5.5 经验库数据模型

```sql
CREATE TABLE experience (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL,
    scenario_hash VARCHAR(64) NOT NULL,   -- 场景指纹
    scenario      TEXT NOT NULL,          -- 场景描述
    strategy      TEXT NOT NULL,          -- 策略/计划
    outcome       VARCHAR(20),           -- SUCCESS/PARTIAL/FAIL
    score         NUMERIC(3,2),          -- 0-1
    lesson        TEXT,                  -- 教训
    embedding     vector(384),           -- 场景语义向量
    source_task   VARCHAR(64),
    hit_count     INT DEFAULT 0,         -- 复用次数
    created_at    TIMESTAMP,
    updated_at    TIMESTAMP
);
CREATE INDEX idx_exp_hash ON experience(scenario_hash);
CREATE INDEX idx_exp_embedding ON experience USING hnsw (embedding vector_cosine_ops);
```

---

## 6. 企业级接入指南

### 6.1 接入检查清单

| 检查项 | 要求 | 企业自评 |
|--------|------|---------|
| 已部署 LLM | DeepSeek/Qwen/GLM/GPT 等 | □ |
| 有向量库 | pgvector/Milvus/Qdrant 等 | □ |
| 有 KV 存储 | Redis/Memcached 等 | □ |
| 有关系数据库 | PostgreSQL/MySQL 等 | □ |
| 可选：图谱库 | Neo4j/Kuzu 等 | □ |

### 6.2 配置示例

```yaml
wenshi:
  llm:
    adapter: deepseek           # deepseek | qwen | openai | custom
    model: deepseek-chat
    api-key: ${DEEPSEEK_API_KEY}
  embedding:
    adapter: bge-small          # bge-small | llm-native | openai | custom
    model-path: models/bge-small.onnx
    dimension: 384
  vectorstore:
    adapter: pgvector           # pgvector | milvus | qdrant | custom
    host: ${PG_HOST}
    database: wenshi
  tenant:
    isolation: row-level        # row-level | schema | database
  memory:
    semantic:
      enabled: true
      graph-store: neo4j        # neo4j | kuzu | none（纯向量降级）
    episodic:
      enabled: true
      retention-days: 365
    procedural:
      enabled: true
    parametric:
      enabled: true
  learning:
    experience:
      enabled: true
      min-score: 0.6
      max-entries: 100000
    skill:
      enabled: true
      min-experiences: 3
      min-success-rate: 0.85
    reflection:
      enabled: true
      trigger-threshold: 0.5
```

### 6.3 接入流程

```
第 1 步：选择适配器（根据企业已有基础设施）
  │
第 2 步：配置 application.yml
  │
第 3 步：启动知识层（DDL 迁移 + 数据导入）
  │
第 4 步：启动推理层（Planner + SolverRouter + Critic）
  │
第 5 步：启动学习层（经验抽取 + 质量评估 + 技能演化）
  │
第 6 步：集成测试 + 效果评估
```

---

## 7. 定型决策汇总

| # | 决策项 | 结论 |
|---|--------|------|
| 1 | embedding 模型 | 标准接口+多适配器，默认 BGE-small |
| 2 | 经验匹配算法 | 标准接口+多策略可配，默认向量+规则 |
| 3 | 多租户安全 | 标准接口+三模式可配，默认行级隔离 |
| 4 | Planner 任务分解 | 混合策略（先模板后 LLM） |
| 5 | Solver 策略路由 | 经验优先四级递减 |
| 6 | Critic 接地验证 | 外部验证优先三级递进 |
| 7 | 语义记忆存储 | 图谱+向量混合 |
| 8 | 情景记忆存储 | 时序+向量 |
| 9 | 程序性记忆存储 | 分类存储统一接口 |
| 10 | 参数化记忆存储 | KV+DB 双写 |
| 11 | 记忆路由策略 | 规则优先+LLM 兜底 |
| 12 | 记忆注入方式 | 按需注入 |
| 13 | 经验抽取方式 | 混合（规则为主+LLM 补充） |
| 14 | 经验质量评估 | 混合（规则初筛+LLM 精评） |
| 15 | 技能封装演化 | 混合（规则提取+LLM 补充） |
| 16 | 反思复盘执行 | 混合（规则匹配+LLM 深度分析） |
| 17 | 学习效果度量 | 混合（在线监控+定期离线评估） |

---

## 8. 后续步骤

1. 架构整体系统设计（含现有系统对接）
2. 制定详细开发计划
3. 启动阶段 A 开发
