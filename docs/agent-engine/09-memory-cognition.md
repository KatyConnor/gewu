# 09 · 记忆与认知 SPI

> 单次 ReAct 让 Agent 能"当下会做"，但要让 Agent 跨会话记住用户偏好、复用历史经验、在失败中学习改进，需要记忆与认知能力。格物 Agent 引擎在 `com.gewu.agent.engine.memory` 与 `com.gewu.agent.engine.cognition` 两个包提供五类记忆与三大认知能力的 SPI，全部带 NoOp 默认实现且不绑定特定向量库或推理引擎。本篇是这两套 SPI 的完整参考。

## 9.1 整体架构

```
   AgentTask 输入
        │
        ▼
   MemoryRouter.inject(domain, messages, taskInput)        ← 检索哪些记忆注入 prompt
        │
        ├─ 调用 MemoryStore.retrieve(domain, query, topK)   ← 语义检索（向量库）
        ├─ 调用 MemoryStore.retrieveByMetadata(domain, filter)
        ├─ 去重 / 截断（token 预算）
        └─ 注入 system prompt
        ▼
   AgentExecutor (ReAct)
        │ 执行产出
        ▼
   ┌─────────────── 编排引擎生命周期 ───────────────┐
   │ EvolutionHook.onNodeComplete(nodeId, result)   │ 记录推理轨迹
   │ ... 图执行 ...
   │ EvolutionHook.onGraphComplete(graphId, result, reflection) │ 经验抽取+技能演化
   │   └─ ReflectionEngine.reflect(executionId, result, goal)  │ 反思
   │       └─ ReflectionEngine.replan(goal, reflection)        │ 重规划
   └───────────────────────────────────────────────┘
        │
        ▼
   ReasoningKernel（编排时按需调用）
   ├─ plan(task, context)         → 子任务列表  (Planner)
   ├─ routeSolver(task)           → 求解策略     (Solver)
   └─ critique(output, acceptances) → 评估结论    (Critic)
        │
        ▼
   MemoryStore.store(MemoryFragment)  ← 把经验/轨迹沉淀回记忆
```

## 9.2 四类记忆 + 经验记忆

`MemoryStore` 的 Javadoc 定义了四类基础记忆，外加 `experience` 一类在 `MemoryFragment.type` 中开放，共五种取值：

| 类型（type） | 含义 | 示例 | 触发时机 |
|--------------|------|------|----------|
| `semantic` | 语义记忆：领域知识 / 规范 / 代码模式 | "Spring Security 配置 OAuth2 的标准模式" | 查知识库时注入 |
| `episodic` | 情景记忆：历史执行事件 / 协作经历 | "上次为该用户实现登录采用了 JWT" | 历史会话检索 |
| `procedural` | 程序性记忆：技能库（演化产物） | "调用 git_commit 工具的标准步骤" | 技能复用 |
| `parametric` | 参数化记忆：用户偏好 / 项目配置 | "该用户偏好 Java 21 + Spring Boot 3" | 用户级个性化 |
| `experience` | 经验记忆：成功/失败经验（`EvolutionHook` 沉淀） | "该领域目标失败原因：缺少边界校验" | 反思与重规划 |

记忆域（`domain`）用于角色/会话隔离，与 `AgentRoleSpec.memoryDomain` 对齐（如 `coding` / `architecture` / `security` 等 12 个角色域）。

## 9.3 MemoryStore SPI

```java
package com.gewu.agent.engine.memory;

import java.util.List;

public interface MemoryStore {
    void store(MemoryFragment fragment);
    List<MemoryFragment> retrieve(String domain, String query, int topK);
    default List<MemoryFragment> retrieveByMetadata(String domain, java.util.Map<String, Object> filter) {
        return java.util.List.of();
    }
    default void clear(String domain) { }
}
```

| 维度 | 说明 |
|------|------|
| **方法契约** | `store` 写入一个片段（含 `vector`，使用方在存储时生成向量）；`retrieve` 语义检索按 `query` 文本返回最相似 `topK` 个片段；`retrieveByMetadata` 按 metadata 过滤；`clear` 删除指定域 |
| **实现要求** | 使用方实现 `retrieve` 需对接向量数据库的相似度检索（向量由调用方传入或存储时生成）。`vector` 字段在 `store` 时由使用方填充（框架不内置 embedding 模型） |
| **NoOp 默认** | `NoOpMemoryStore`：`store` 空操作，`retrieve` 返回 `List.of()` —— 无记忆能力 |
| **业务适配备注** | 格物平台对接 pgvector / Milvus，向量由内部 embedding 服务预生成写入 `memory_fragment` 表 |

## 9.4 MemoryFragment 模型

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MemoryFragment {
    private String id;                       // 记忆 ID
    private String domain;                   // 记忆域
    private String type;                     // semantic/episodic/procedural/parametric/experience
    private String content;                  // 内容
    private float[] vector;                  // 向量（由使用方在存储时生成）
    private java.util.Map<String, Object> metadata;  // 关联元数据
    private double score;                    // 相关度评分（检索时填充）
}
```

`score` 在检索返回时由向量库填充（余弦相似度等）。`metadata` 常用键：`goalId`、`agentId`、`sessionId`、`sdlcPhase`、`createdAt`，便于 `retrieveByMetadata` 过滤与 `EvolutionHook` 关联。

## 9.5 MemoryRouter SPI —— 记忆注入流程

```java
package com.gewu.agent.engine.memory;

import com.gewu.agent.engine.llm.model.Message;
import java.util.List;

public interface MemoryRouter {
    List<Message> inject(String domain, List<Message> messages, String taskInput);
}
```

`inject` 的职责是"判断任务需要哪些记忆并注入消息"。设计哲学为"规则优先 + LLM 兜底"：

```
inject(domain, messages, taskInput):
   1. 规则路由：按 taskInput 关键词 / domain 当前阶段，决定检索哪些记忆类型/域
      例：coding 域 + 关键词"重构" → 检索 procedural(skill) + episodic(历史重构)
   2. 检索：MemoryStore.retrieve(domain, taskInput, topK)
            MemoryStore.retrieveByMetadata(domain, filter)
   3. 去重 / 截断（token 预算）：按 score 合并，超限截断
   4. 注入：拼成 system prompt 片段（"相关历史经验如下: ..."）插入 messages 头部
   5. 返回注入后的 messages
```

| 维度 | 说明 |
|------|------|
| **方法契约** | 输入原始 `messages` 与 `taskInput`，输出注入记忆后的 `messages`；不修改原列表 |
| **实现要求** | 使用方实现检索路由规则与 token 预算控制；可借助 LLM 判断"该检索什么"作为兜底 |
| **NoOp 默认** | `NoOpMemoryRouter`：直接返回原 `messages`——不注入任何记忆 |
| **业务适配备注** | 格物平台实现 LLM 路由兜底 + 规则优先的混合策略 |

## 9.6 ReasoningKernel —— 三大认知能力

```java
package com.gewu.agent.engine.cognition;

import java.util.List;

public interface ReasoningKernel {
    ReasoningResult plan(String task, String context);              // Planner
    String routeSolver(String task);                               // Solver 路由
    ReasoningResult critique(String output, List<String> acceptances); // Critic
}
```

### ReasoningResult 模型

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReasoningResult {
    private String type;            // PLAN / SOLVE / CRITIC
    private List<String> subtasks;  // 规划的子任务列表（PLAN）
    private String answer;          // 求解答案（SOLVE）
    private String verdict;         // 评估结论（CRITIC）
    private double score;           // 评估评分（CRITIC，0~1）
    private boolean accepted;       // 是否通过验收（CRITIC）
    private String reasoning;      // 推理过程摘要
}
```

### 三大能力

| 能力 | 方法 | 返回 | 编排层用途 |
|------|------|------|------------|
| **Planner** | `plan(task, context)` | `ReasoningResult{type="PLAN", subtasks:[...]}` | `PlanExecuteRuntime` 拆解任务；`GoalPlanner` 二级路由的 LLM 规划 |
| **Solver** | `routeSolver(task)` | 策略字符串：`TOOL_EXECUTION` / `CODE_GENERATION` / `KNOWLEDGE_RETRIEVAL` / `DIRECT_ANSWER` | 编排节点决定走工具/代码生成/知识检索 |
| **Critic** | `critique(output, acceptances)` | `ReasoningResult{type="CRITIC", score, accepted, verdict}` | `ReflexionRuntime` 判定是否重做；`AutonomousExecutor.verifyGoal` 验收 |

`NoOpReasoningKernel`：`plan` 返回单子任务 `[task]`；`routeSolver` 返回 `DIRECT_ANSWER`；`critique` 返回 `accepted=true, score=1.0, verdict="NoOp: 默认通过"`。即未对接认知引擎时，编排层不依赖认知能力也可运行（默认全通过）。

## 9.7 EvolutionHook —— 进化闭环

```java
package com.gewu.agent.engine.cognition;

public interface EvolutionHook {
    void onNodeComplete(String nodeId, String nodeResult);                              // 节点完成
    default String onGraphComplete(String graphId, String result, String reflection);   // 图完成
    default void onGoalFailure(String goalId, String errorMessage);                    // 目标失败
    default void onGoalSuccess(String goalId, String result);                         // 目标成功
}
```

`EvolutionHook` 嵌入编排引擎生命周期，自动触发进化闭环。四个钩子的职责：

| 钩子 | 触发时机 | 典型动作 |
|------|----------|----------|
| `onNodeComplete` | 每个 AGENT 节点执行完 | 记录推理轨迹（思考链/工具调用/产出） |
| `onGraphComplete` | 整张图执行完 | 反思 + 经验抽取 + 质量评估 + 技能演化，返回抽取的经验 JSON |
| `onGoalFailure` | `AutonomousExecutor` 目标失败 | 失败经验抽取（避免重蹈覆辙），写入 `experience` 记忆 |
| `onGoalSuccess` | 目标成功 | 成功经验沉淀，写入 `experience` 记忆 |

`onGraphComplete` 的 `reflection` 参数由使用方在实现内调用 `ReflectionEngine.reflect` 填充，返回值（经验 JSON 字符串）可回写 `MemoryStore` 的 `experience` 类型记忆。

`NoOpEvolutionHook`：`onNodeComplete` 空操作，`onGraphComplete` 返回 `null`——不触发任何进化。

## 9.8 ReflectionEngine —— 反思重规划

```java
package com.gewu.agent.engine.cognition;

public interface ReflectionEngine {
    String reflect(String executionId, String result, String goal);
    default String replan(String goal, String reflection) { return goal; }
}
```

| 方法 | 说明 |
|------|------|
| `reflect` | 评估整体执行质量并输出改进建议（反思结论），供 `ReflexionRuntime` 重做或 `GoalPlanner` 重规划 |
| `replan` | 基于反思结论改进原目标描述，默认原样返回 |

`NoOpReflectionEngine`：`reflect` 返回 `"NoOp: 无反思"`——不执行反思。`AutonomousExecutor` 的 `reflection` 事件（验收未通过时）正是把 `reflect` 的结论用于下一轮重规划的触发点。

## 9.9 使用方对接示例

### 9.9.1 对接 pgvector 向量库

```java
@Component
public class PgVectorMemoryStore implements MemoryStore {

    private final JdbcTemplate jdbc;        // 使用方数据访问
    private final EmbeddingService embed;    // 使用方 embedding 服务

    @Override
    public void store(MemoryFragment fragment) {
        if (fragment.getVector() == null && fragment.getContent() != null) {
            fragment.setVector(embed.embed(fragment.getContent()));  // 生成向量
        }
        jdbc.update("INSERT INTO memory_fragment(id, domain, type, content, vector, metadata, score) " +
                "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)",
                fragment.getId(), fragment.getDomain(), fragment.getType(),
                fragment.getContent(), fragment.getVector(),
                toJson(fragment.getMetadata()), fragment.getScore());
    }

    @Override
    public List<MemoryFragment> retrieve(String domain, String query, int topK) {
        float[] qv = embed.embed(query);
        return jdbc.query("SELECT id, content, metadata, vector <=> ? AS score " +
                        "FROM memory_fragment WHERE domain=? ORDER BY score LIMIT ?",
                (rs, i) -> MemoryFragment.builder()
                        .id(rs.getString("id")).domain(domain)
                        .content(rs.getString("content")).score(rs.getDouble("score")).build(),
                qv, domain, topK);
    }

    @Override
    public List<MemoryFragment> retrieveByMetadata(String domain, Map<String, Object> filter) {
        // 拼建 jsonb metadata 过滤条件 ...
        return List.of();
    }
}
```

### 9.9.2 对接认知引擎（ReasoningKernel）

```java
@Component
public class WenshiReasoningKernel implements ReasoningKernel {

    private final CognitionClient cognition;   // 使用方认知引擎客户端

    @Override
    public ReasoningResult plan(String task, String context) {
        var resp = cognition.plan(task, context);   // 调用认知引擎 Planner
        return ReasoningResult.builder()
                .type("PLAN").subtasks(resp.getSubtasks()).reasoning(resp.getReasoning()).build();
    }

    @Override
    public String routeSolver(String task) {
        return switch (cognition.classify(task)) {   // 路由求解策略
            case "tool" -> "TOOL_EXECUTION";
            case "code" -> "CODE_GENERATION";
            case "kb"   -> "KNOWLEDGE_RETRIEVAL";
            default     -> "DIRECT_ANSWER";
        };
    }

    @Override
    public ReasoningResult critique(String output, List<String> acceptances) {
        var v = cognition.evaluate(output, acceptances);
        return ReasoningResult.builder()
                .type("CRITIC").score(v.getScore()).accepted(v.isPass()).verdict(v.getVerdict()).build();
    }
}
```

### 9.9.3 实现进化闭环

```java
@Component @RequiredArgsConstructor
public class LearningEvolutionHook implements EvolutionHook {
    private final MemoryStore store;
    private final ReflectionEngine reflectionEngine;

    @Override public void onNodeComplete(String nodeId, String nodeResult) {
        store.store(MemoryFragment.builder()
                .id(UUID.randomUUID().toString()).domain("trace")
                .type("episodic").content("node=" + nodeId + " result=" + nodeResult).build());
    }

    @Override public String onGraphComplete(String graphId, String result, String reflection) {
        String exp = extractExperience(result, reflection);   // 使用方经验抽取逻辑
        store.store(MemoryFragment.builder()
                .id(UUID.randomUUID().toString()).domain("experience")
                .type("experience").content(exp).build());
        return exp;
    }

    @Override public void onGoalFailure(String goalId, String errorMessage) {
        store.store(MemoryFragment.builder()
                .id(UUID.randomUUID().toString()).domain("experience")
                .type("experience").content("FAILURE goal=" + goalId + " err=" + errorMessage).build());
    }
}
```

## 9.10 自动装配

`AgentEngineAutoConfiguration` 装配以下 NoOp 默认 Bean（均 `@ConditionalOnMissingBean`）：`memoryStore`(NoOpMemoryStore)、`memoryRouter`(NoOpMemoryRouter)、`reasoningKernel`(NoOpReasoningKernel)、`evolutionHook`(NoOpEvolutionHook)、`reflectionEngine`(NoOpReflectionEngine)。使用方注册任意实现即覆盖。详见 [10 SPI 规范](10-spi-reference.md)。

## 9.11 小结

记忆与认知 SPI 是 Agent 从"当下会做"走向"持续变强"的关键。`MemoryStore`/`MemoryRouter` 让 Agent 跨会话携带上下文与经验，`ReasoningKernel` 提供规划、求解、评估三大认知能力并喂给编排层运行时与自主执行器，`EvolutionHook`/`ReflectionEngine` 在图/目标生命周期自动触发反思与经验沉淀。五者通过 NoOp 默认保证零业务实现可运行，又为对接向量数据库与认知引擎预留了完整的扩展面。这使格物 Agent 引擎既有开箱即用的可用性，又有"越用越聪明"的演化潜力。