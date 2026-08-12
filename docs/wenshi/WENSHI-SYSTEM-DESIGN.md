# Wenshi（格物知行）—— 整体系统设计与现有架构对接

> 版本：v1.0  ·  日期：2026-07-16  ·  状态：设计完成
>
> 本文档描述 Wenshi 与现有 gewu-platform 的完整对接方案，
> 包括模块划分、包结构、接口契约、数据模型、迁移路径。

---

## 1. 系统总体结构

### 1.1 模块划分

```
gewu-platform/
├── gewu-interface/          # 接口层（Controller + 适配层配置）
├── gewu-application/        # 应用层（Wenshi 核心业务逻辑）
├── gewu-domain/             # 领域层（实体 + 值对象 + 仓储接口）
├── gewu-infrastructure/     # 基础设施层（LLM 客户端 + 存储实现）
├── gewu-common/             # 公共层（工具 + 异常 + 上下文）
└── gewu-sandbox/            # 沙箱层（代码执行）
```

### 1.2 Wenshi 在现有模块中的分布

| Wenshi 层级 | 所在模块 | 包路径 |
|------------|---------|--------|
| 适配层 | gewu-infrastructure | com.gewu.infrastructure.wenshi.adapter |
| 知识层 | gewu-application | com.gewu.application.wenshi.knowledge |
| 推理层 | gewu-application | com.gewu.application.wenshi.reasoning |
| 学习层 | gewu-application | com.gewu.application.wenshi.learning |
| 知识层数据 | gewu-domain | com.gewu.domain.wenshi.knowledge |
| 学习层数据 | gewu-domain | com.gewu.domain.wenshi.learning |
| 知识存储 | gewu-infrastructure | com.gewu.infrastructure.wenshi.store |
| API 入口 | gewu-interface | com.gewu.interfaceapi.controller（扩展现有） |

---

## 2. 现有系统 → Wenshi 的演进映射

### 2.1 现有组件与 Wenshi 的关系

| 现有组件 | 当前职责 | Wenshi 中的演变 |
|---------|---------|----------------|
| `AiChatController` | 对话入口 | 保持不变，内部委托给 Wenshi |
| `AgentExecutionEngine` | 执行引擎（ReAct 循环） | 演变为 `WenshiReasoningEngine` |
| `LlmClientFactory` | LLM 客户端工厂 | 演变为 `LlmAdapter`（适配层） |
| `ToolExecutionService` | 工具执行 | 演变为程序性记忆 + Skill 调度 |
| `SessionContextService` | 会话上下文 | 演变为情景记忆 + 记忆路由 |
| `ContextCompressor` | 上下文压缩 | 保留，作为记忆注入的压缩策略 |
| `SessionMessage` 表 | 会话消息 | 演变为情景记忆的一部分 |
| `AgentTool` 表 | 工具定义 | 演变为程序性记忆的工具存储 |
| `McpServer` 表 | MCP 服务 | 保留，作为外部工具接入 |

### 2.2 演进策略：绞杀者模式

```
阶段 A：Wenshi 并行运行，现有系统不动
  │      - 新增 Wenshi 模块，现有代码不改
  │      - 通过配置开关选择走 Wenshi 还是原路径
  │
阶段 B：核心路径切换到 Wenshi
  │      - 对话入口 → WenshiReasoningEngine
  │      - 原 AgentExecutionEngine 降级为备选
  │
阶段 C：原路径下线
         - 移除原 AgentExecutionEngine
         - Wenshi 成为唯一路径
```

---

## 3. 包结构设计

### 3.1 gewu-infrastructure 新增包

```
com.gewu.infrastructure.wenshi/
├── adapter/                          # 适配层
│   ├── LlmAdapter.java               # LLM 适配器接口
│   ├── DeepSeekAdapter.java          # DeepSeek 实现
│   ├── QwenAdapter.java              # Qwen 实现
│   ├── OpenAiAdapter.java            # OpenAI 实现
│   ├── EmbeddingAdapter.java         # 嵌入适配器接口
│   ├── BgeSmallAdapter.java          # BGE-small 实现
│   ├── LlmNativeEmbeddingAdapter.java# LLM 原生 embedding 实现
│   ├── VectorStoreAdapter.java       # 向量库适配器接口
│   ├── PgvectorAdapter.java          # pgvector 实现
│   └── TenantIsolationAdapter.java   # 租户隔离适配器接口
│
├── store/                            # 存储实现
│   ├── SemanticStore.java            # 语义记忆存储
│   ├── EpisodicStore.java            # 情景记忆存储
│   ├── ProceduralStore.java          # 程序性记忆存储
│   ├── ParametricStore.java          # 参数化记忆存储
│   └── ExperienceStore.java          # 经验存储
│
└── graph/                            # 图谱（阶段 D）
    ├── GraphStore.java
    └── Neo4jStore.java
```

### 3.2 gewu-application 新增包

```
com.gewu.application.wenshi/
├── knowledge/                        # 知识层
│   ├── MemoryRouter.java             # 记忆路由
│   ├── MemoryRouterImpl.java         # 路由实现
│   ├── SemanticMemoryService.java    # 语义记忆服务
│   ├── EpisodicMemoryService.java    # 情景记忆服务
│   ├── ProceduralMemoryService.java  # 程序性记忆服务
│   ├── ParametricMemoryService.java  # 参数化记忆服务
│   └── KnowledgeIngestionService.java# 知识写入管线
│
├── reasoning/                        # 推理层
│   ├── WenshiReasoningEngine.java    # Wenshi 推理引擎（入口）
│   ├── Planner.java                  # 任务规划器
│   ├── PlannerImpl.java              # 混合规划实现
│   ├── SolverRouter.java             # 策略路由
│   ├── SolverRouterImpl.java         # 经验优先路由实现
│   ├── SymbolicSolver.java           # 符号求解器
│   ├── ToolSolver.java               # 工具求解器
│   ├── KnowledgeSolver.java          # 知识求解器
│   ├── ExperienceSolver.java         # 经验求解器
│   ├── Critic.java                   # 结果验证器
│   ├── CriticImpl.java               # 三级递进验证实现
│   └── ReasoningTracer.java          # 推理轨迹记录
│
├── learning/                         # 学习层
│   ├── ExperienceExtractor.java      # 经验抽取器
│   ├── ExperienceExtractorImpl.java  # 混合抽取实现
│   ├── QualityAssessor.java          # 质量评估器
│   ├── QualityAssessorImpl.java      # 混合评估实现
│   ├── SkillEvolver.java             # 技能演化器
│   ├── SkillEvolverImpl.java         # 混合演化实现
│   ├── ReflectionAgent.java          # 反思复盘代理
│   ├── ReflectionAgentImpl.java      # 混合复盘实现
│   └── LearningMetricsService.java   # 学习效果度量
│
└── config/                           # 配置
    └── WenshiProperties.java         # Wenshi 配置属性
```

### 3.3 gewu-domain 新增包

```
com.gewu.domain.wenshi/
├── knowledge/
│   ├── SemanticFragment.java         # 语义记忆片段
│   ├── EpisodicEvent.java            # 情景事件
│   ├── ProceduralMemory.java         # 程序性记忆
│   ├── Skill.java                    # 技能
│   └── UserProfile.java              # 用户画像（参数化记忆）
│
└── learning/
    ├── Experience.java               # 经验
    ├── ReflectionReport.java         # 复盘报告
    └── LearningMetrics.java          # 学习指标
```

### 3.4 gewu-infrastructure/mapper 新增

```
com.gewu.infrastructure.mapper.wenshi/
├── SemanticFragmentMapper.java
├── EpisodicEventMapper.java
├── ProceduralMemoryMapper.java
├── ExperienceMapper.java
├── SkillMapper.java
└── UserProfileMapper.java
```

---

## 4. 核心接口契约

### 4.1 WenshiReasoningEngine（推理引擎入口）

```java
package com.gewu.application.wenshi.reasoning;

/**
 * Wenshi 推理引擎 —— 替代现有 AgentExecutionEngine 的入口。
 * 对前端透明，AiChatController 逐步切换到本接口。
 */
public interface WenshiReasoningEngine {

    /**
     * 同步推理
     */
    WenshiReasoningResult reason(WenshiReasoningRequest request);

    /**
     * 流式推理
     */
    Flux<WenshiReasoningChunk> reasonStream(WenshiReasoningRequest request);
}

@Data
@Builder
public class WenshiReasoningRequest {
    private String agentId;
    private String sessionId;
    private String userId;
    private String tenantId;
    private String message;
    private Map<String, Object> context;
    private ReasoningConstraints constraints;
}

@Data
@Builder
public class WenshiReasoningResult {
    private String answer;
    private Object structuredOutput;
    private PlanTree plan;
    private List<TraceStep> trace;
    private List<SemanticFragment> usedKnowledge;
    private Experience reusedExperience;
    private TokenStatistics tokenStats;
    private long reasoningTimeMs;
    private boolean fromExperience;  // 是否来自经验复用
}
```

### 4.2 MemoryRouter（记忆路由）

```java
package com.gewu.application.wenshi.knowledge;

/**
 * 记忆路由 —— 决定从哪些记忆源取数据。
 * 替代现有 SessionContextService 的上下文构建逻辑。
 */
public interface MemoryRouter {

    /**
     * 根据任务上下文路由到合适的记忆源
     */
    RoutingResult route(TaskContext context);

    /**
     * 按需注入：先注入摘要，LLM 需要时再展开
     */
    InjectionPlan planInjection(RoutingResult routing, int maxTokens);
}

@Data
public class RoutingResult {
    private List<SemanticFragment> semanticMemories;
    private List<EpisodicEvent> episodicMemories;
    private List<ProceduralMemory> proceduralMemories;
    private UserProfile parametricMemory;
    private RoutingDecision decision;  // 路由决策（可追溯）
}
```

### 4.3 LlmAdapter（LLM 适配）

```java
package com.gewu.infrastructure.wenshi.adapter;

/**
 * LLM 适配器 —— 替代现有 LlmClientFactory。
 * 屏蔽不同 LLM 提供商的差异。
 */
public interface LlmAdapter {

    /**
     * 同步对话
     */
    LlmResponse chat(LlmRequest request);

    /**
     * 流式对话
     */
    Flux<LlmChunk> chatStream(LlmRequest request);

    /**
     * 嵌入（如果 LLM 支持）
     */
    default Optional<float[]> embed(String text) {
        return Optional.empty();
    }

    /**
     * 获取模型能力
     */
    LlmCapabilities getCapabilities();

    /**
     * 适配器名称
     */
    String getProvider();
}
```

### 4.4 ExperienceExtractor（经验抽取）

```java
package com.gewu.application.wenshi.learning;

/**
 * 经验抽取器 —— 从推理轨迹中抽取经验。
 * 规则为主 + LLM 补充。
 */
public interface ExperienceExtractor {

    /**
     * 从推理轨迹抽取经验
     * @param trace 完整推理轨迹
     * @return 结构化经验
     */
    Experience extract(ReasoningTrace trace);
}
```

---

## 5. 数据模型（DDL）

### 5.1 语义记忆片段

```sql
CREATE TABLE wenshi_semantic_fragment (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    graph_node_id VARCHAR(64),              -- 关联图谱节点（阶段 D）
    content       TEXT NOT NULL,
    source        VARCHAR(32) NOT NULL,     -- MANUAL/IMPORT/DIALOGUE/TOOL
    confidence    NUMERIC(3,2) DEFAULT 1.0,
    embedding     vector(384),
    metadata      JSONB,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_wsf_tenant ON wenshi_semantic_fragment(tenant_id);
CREATE INDEX idx_wsf_embedding ON wenshi_semantic_fragment USING hnsw (embedding vector_cosine_ops);
```

### 5.2 情景事件

```sql
CREATE TABLE wenshi_episodic_event (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL,
    user_id       BIGINT NOT NULL,
    session_id    VARCHAR(64),
    event_type    VARCHAR(32) NOT NULL,     -- MESSAGE/TOOL_CALL/TASK_START/TASK_END/ERROR
    content       TEXT NOT NULL,
    embedding     vector(384),
    metadata      JSONB,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_wee_tenant_time ON wenshi_episodic_event(tenant_id, created_at);
CREATE INDEX idx_wee_user ON wenshi_episodic_event(tenant_id, user_id);
CREATE INDEX idx_wee_embedding ON wenshi_episodic_event USING hnsw (embedding vector_cosine_ops);
```

### 5.3 程序性记忆

```sql
CREATE TABLE wenshi_procedural_memory (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL,
    type          VARCHAR(16) NOT NULL,     -- TOOL/SOP/SKILL
    name          VARCHAR(128) NOT NULL,
    description   TEXT,
    definition    JSONB NOT NULL,           -- 结构化定义
    embedding     vector(384),              -- 用于语义检索
    usage_count   INT DEFAULT 0,
    success_rate  NUMERIC(5,2),
    skill_level   INT DEFAULT 1,
    learned_from  VARCHAR(64),              -- 来源经验 id
    status        INT DEFAULT 1,            -- 1=活跃 0=休眠 -1=废弃
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_wpm_tenant_type ON wenshi_procedural_memory(tenant_id, type);
CREATE INDEX idx_wpm_embedding ON wenshi_procedural_memory USING hnsw (embedding vector_cosine_ops);
```

### 5.4 经验库

```sql
CREATE TABLE wenshi_experience (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL,
    scenario_hash VARCHAR(64) NOT NULL,     -- 场景指纹
    scenario      TEXT NOT NULL,            -- 场景描述
    strategy      TEXT NOT NULL,            -- 策略/计划
    outcome       VARCHAR(20) NOT NULL,     -- SUCCESS/PARTIAL/FAIL
    score         NUMERIC(3,2) NOT NULL,    -- 0-1
    lesson        TEXT,                     -- 教训
    embedding     vector(384),              -- 场景语义向量
    source_task   VARCHAR(64),              -- 来源任务 id
    hit_count     INT DEFAULT 0,            -- 被复用次数
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_we_tenant_hash ON wenshi_experience(tenant_id, scenario_hash);
CREATE INDEX idx_we_embedding ON wenshi_experience USING hnsw (embedding vector_cosine_ops);
```

### 5.5 用户画像（参数化记忆）

```sql
CREATE TABLE wenshi_user_profile (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL,
    user_id       BIGINT NOT NULL,
    profile_key   VARCHAR(128) NOT NULL,
    profile_value JSONB NOT NULL,
    source        VARCHAR(32),              -- MANUAL/BEHAVIOR_LEARNED
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(tenant_id, user_id, profile_key)
);
```

### 5.6 推理轨迹

```sql
CREATE TABLE wenshi_reasoning_trace (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL,
    session_id    VARCHAR(64),
    task_id       VARCHAR(64) NOT NULL,
    plan_tree     JSONB,                    -- 完整计划树
    trace_steps   JSONB[],                  -- 推理轨迹步骤
    used_knowledge BIGINT[],                -- 使用的知识 fragment ids
    reused_experience_id BIGINT,            -- 复用的经验 id
    token_stats   JSONB,                    -- token 消耗统计
    reasoning_ms  BIGINT,                   -- 推理耗时
    from_experience BOOLEAN DEFAULT FALSE,  -- 是否来自经验复用
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_wrt_tenant_session ON wenshi_reasoning_trace(tenant_id, session_id);
```

---

## 6. 现有系统对接方案

### 6.1 对话入口对接

**现有流程**：
```
AiChatController → AgentExecutionEngine → LlmClient → LLM
```

**对接后流程**：
```
AiChatController → WenshiReasoningEngine → LlmAdapter → LLM
                         │
                         ├→ MemoryRouter → 知识层
                         ├→ SolverRouter → 经验/知识/工具/LLM
                         └→ ExperienceExtractor → 学习层
```

**对接方式**：通过配置开关渐进切换

```yaml
wenshi:
  enabled: true                    # 总开关
  routing:
    chat: wenshi                   # wenshi | legacy
    stream: wenshi                 # wenshi | legacy
```

### 6.2 会话记忆对接

**现有**：`SessionContextService.buildContextMessages()` 从 `session_message` 表加载历史

**对接后**：`MemoryRouter` 从四类记忆中按需检索，`session_message` 表降级为原始日志

```java
// 阶段 A：双写（Wenshi 记忆 + 原有 session_message）
// 阶段 B：只写 Wenshi 记忆，session_message 作为备份
// 阶段 C：session_message 只读（兼容旧查询）
```

### 6.3 工具系统对接

**现有**：`AgentTool` 表 + `ToolExecutionService`

**对接后**：`AgentTool` 表映射为程序性记忆的工具类型

```java
// 现有 AgentTool → 自动导入为 ProceduralMemory(type=TOOL)
// 新增工具 → 同时写入 AgentTool（兼容）和 ProceduralMemory
// 阶段 B 后 → 只写 ProceduralMemory，AgentTool 只读
```

### 6.4 LLM 客户端对接

**现有**：`LlmClientFactory` + `DeepSeekClient` / `QwenClient`

**对接后**：`LlmAdapter` 接口 + 适配器实现

```java
// DeepSeekAdapter 包装 DeepSeekClient
// QwenAdapter 包装 QwenClient
// 现有代码不改，新增适配器层
```

---

## 7. 配置设计

### 7.1 application.yml

```yaml
wenshi:
  enabled: true

  # 路由开关：wenshi | legacy
  routing:
    chat: wenshi
    stream: wenshi

  # LLM 适配器
  llm:
    adapter: deepseek
    model: deepseek-chat
    api-key: ${DEEPSEEK_API_KEY}
    timeout-seconds: 30

  # 嵌入适配器
  embedding:
    adapter: bge-small
    model-path: models/bge-small-onnx
    dimension: 384

  # 向量库
  vectorstore:
    adapter: pgvector
    host: ${PG_HOST:localhost}
    port: ${PG_PORT:5432}
    database: ${PG_DATABASE:gewu}
    username: ${PG_USERNAME:postgres}
    password: ${PG_PASSWORD:}

  # 租户隔离
  tenant:
    isolation: row-level    # row-level | schema | database
    audit-log: true

  # 知识层
  knowledge:
    semantic:
      enabled: true
      max-fragments-per-query: 5
    episodic:
      enabled: true
      retention-days: 365
      max-events-per-query: 10
    procedural:
      enabled: true
    parametric:
      enabled: true
      behavior-learning: true

  # 推理层
  reasoning:
    planner:
      template-match-first: true
      max-subgoals: 10
    solver:
      experience-first: true
      max-llm-rounds: 5
    critic:
      external-verify-first: true
      max-retry-rounds: 3

  # 学习层
  learning:
    experience:
      enabled: true
      min-score: 0.6
      max-entries: 100000
      auto-extract: true
    skill:
      enabled: true
      min-experiences: 3
      min-success-rate: 0.85
      evolve-cron: "0 0 2 * * ?"  # 每天凌晨 2 点
    reflection:
      enabled: true
      trigger-threshold: 0.5
    metrics:
      enabled: true
      offline-eval-cron: "0 0 3 * * 0"  # 每周日凌晨 3 点

  # 记忆注入
  injection:
    mode: on-demand           # on-demand | full
    max-tokens: 2000
    summary-length: 100
```

### 7.2 适配器自动装配

```java
@Configuration
@EnableConfigurationProperties(WenshiProperties.class)
public class WenshiAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "wenshi.llm", name = "adapter", havingValue = "deepseek")
    public LlmAdapter deepSeekAdapter(WenshiProperties props) {
        return new DeepSeekAdapter(props.getLlm());
    }

    @Bean
    @ConditionalOnProperty(prefix = "wenshi.embedding", name = "adapter", havingValue = "bge-small")
    public EmbeddingAdapter bgeSmallAdapter(WenshiProperties props) {
        return new BgeSmallAdapter(props.getEmbedding());
    }

    @Bean
    @ConditionalOnProperty(prefix = "wenshi.vectorstore", name = "adapter", havingValue = "pgvector")
    public VectorStoreAdapter pgvectorAdapter(WenshiProperties props) {
        return new PgvectorAdapter(props.getVectorstore());
    }
}
```

---

## 8. 迁移路径

### 8.1 阶段 A（第 1-4 周）：基础设施 + 知识层

| 周 | 任务 | 现有系统影响 |
|----|------|------------|
| W1 | pgvector 扩展 + DDL 迁移 | 无影响 |
| W1 | EmbeddingAdapter + BGE-small 部署 | 无影响 |
| W2 | SemanticFragment + EpisodicEvent 存储 | 无影响 |
| W2 | MemoryRouter 基础实现 | 无影响 |
| W3 | 知识导入管线（现有 session_message → 情景记忆） | 无影响 |
| W3 | 程序性记忆（现有 AgentTool → 程序性记忆） | 无影响 |
| W4 | 参数化记忆 + 用户画像 | 无影响 |

### 8.2 阶段 B（第 5-8 周）：推理层 + 学习层

| 周 | 任务 | 现有系统影响 |
|----|------|------------|
| W5 | WenshiReasoningEngine 骨架 | 无影响（并行运行） |
| W5 | Planner + SolverRouter | 无影响 |
| W6 | Critic + 推理轨迹记录 | 无影响 |
| W6 | 经验抽取 + 质量评估 | 无影响 |
| W7 | 技能演化 + 反思复盘 | 无影响 |
| W7 | 对话入口切换（配置开关） | 灰度切换 |
| W8 | 集成测试 + 效果评估 | 对比评估 |

### 8.3 阶段 C（第 9-12 周）：评估 + 优化

| 周 | 任务 | 现有系统影响 |
|----|------|------------|
| W9-W10 | 对比实验（4 基线 × 6 场景） | 数据收集 |
| W11 | 效果分析 + 调优 | 优化 Wenshi |
| W12 | 论文/PoC 报告 | 总结 |

---

## 9. 关键约束

### 9.1 阶段 A 不改变现有功能

- 现有 `AgentExecutionEngine` 继续运行
- 现有 `AiChatController` 继续运行
- Wenshi 模块独立开发、独立测试
- 通过配置开关控制是否启用

### 9.2 数据双写保证安全

- 阶段 A/B：Wenshi 记忆和原有 session_message 双写
- 确保切换失败时可以回退

### 9.3 性能约束

- 记忆检索延迟 < 200ms（P99）
- 推理层额外开销 < 500ms（不含 LLM 调用）
- 知识写入异步化（不阻塞对话）

---

## 10. 下一步

本文档完成后，下一步是：
1. 制定详细的开发任务拆解（精确到天）
2. 启动阶段 A 第 1 周开发
