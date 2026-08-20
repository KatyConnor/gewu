# 格物平台 AI 交互架构优化执行计划

> 基于 `AI交互架构分析与优化计划.docx` 的代码级验证结果，结合 codebase-memory 代码图谱分析，制定的可逐步执行方案。
> 每个步骤包含：目标、涉及文件（精确路径+行号）、具体修改内容、前置依赖、验证方法。

---

## 前置：codebase-memory 辅助工具使用约定

项目已索引至 codebase-memory（`~/.cache/codebase-memory-mcp/home-wnn-devcode-ai-code-gewu-platform.db`，47MB）。每个步骤执行前用以下命令做影响分析：

```bash
# 查找某方法的所有调用方（修改前确认影响范围）
codebase-memory-mcp cli trace_path '{"function_name":"方法名","project":"gewu-platform","direction":"callers","depth":3}'

# 查找符号定义和引用
codebase-memory-mcp cli search_graph '{"project":"gewu-platform","name_pattern":"类名或方法名","limit":20}'

# 获取符号源码
codebase-memory-mcp cli get_code_snippet '{"qualified_name":"com.gewu.xxx.方法名","project":"gewu-platform"}'
```

---

## 阶段一：P0 阻断性修复（2-3 周）

### 步骤 1：修复 Wenshi 数据库 Schema 缺失列

**目标**：`wenshi_semantic_fragment` 和 `wenshi_experience` 表缺少 `created_by`、`updated_by`、`deleted` 列，导致 `BaseEntity` 映射和 `PgvectorAdapter.upsert()` SQL 失败。

**涉及文件**：
- 新建 `gewu-interface/src/main/resources/db/migration/wenshi/V10__add_base_entity_columns.sql`

**具体修改**：创建 Flyway 迁移脚本，为两张表补齐 `BaseEntity` 所需列：
```sql
-- wenshi_semantic_fragment 补列
ALTER TABLE wenshi_semantic_fragment ADD COLUMN IF NOT EXISTS created_by VARCHAR(26);
ALTER TABLE wenshi_semantic_fragment ADD COLUMN IF NOT EXISTS updated_by VARCHAR(26);
ALTER TABLE wenshi_semantic_fragment ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_wsf_deleted ON wenshi_semantic_fragment(deleted);

-- wenshi_experience 补列
ALTER TABLE wenshi_experience ADD COLUMN IF NOT EXISTS created_by VARCHAR(26);
ALTER TABLE wenshi_experience ADD COLUMN IF NOT EXISTS updated_by VARCHAR(26);
ALTER TABLE wenshi_experience ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_we_deleted ON wenshi_experience(deleted);

-- wenshi_episodic_event / wenshi_procedural_memory / wenshi_reasoning_trace / wenshi_user_profile 同理补列
-- （执行时检查 V8 迁移确认哪些表也缺列，统一补齐）
```

**验证**：启动应用，确认 Flyway 迁移成功；用 psql 连接 wenshi 库确认列存在。

---

### 步骤 2：SemanticFragment 实体增加 embedding 字段

**目标**：实体缺少 `embedding` 字段，导致 MyBatis-Plus 无法读写向量列。

**涉及文件**：
- `gewu-domain/src/main/java/com/gewu/domain/wenshi/knowledge/SemanticFragment.java`（行 13-19，字段列表）

**具体修改**：在字段列表中增加：
```java
@TableField("embedding", insertStrategy = FieldStrategy.IGNORED, updateStrategy = FieldStrategy.IGNORED)
private String embedding; // pgvector vector(384) 类型，通过 PgvectorAdapter 操作，MyBatis-Plus 不直接映射
```
> 注意：`vector` 类型不能直接用 MyBatis-Plus 映射为 Java 类型。embedding 的读写通过 `PgvectorAdapter` 的原生 SQL 完成，实体字段仅用于占位。或者使用 `@TableField(exist = false)` 标注不参与 MyBatis-Plus 映射，在 `SemanticMemoryService` 中直接调用 `PgvectorAdapter`。

**验证**：编译通过，MyBatis-Plus 启动不报字段映射错误。

---

### 步骤 3：连通 SemanticMemoryService 与 PgvectorAdapter（核心修复）

**目标**：`ingest()` 丢弃向量、`search()` 按时间排序而非向量检索。连通已实现的 `PgvectorAdapter`。

**涉及文件**：
- `gewu-application/src/main/java/com/gewu/application/wenshi/knowledge/SemanticMemoryService.java`

**当前代码**（行 37-39 注入 + 行 54-72 ingest + 行 87-106 search）：
- 注入：`SemanticFragmentMapper mapper`、`EmbeddingAdapter embeddingAdapter`、`CacheService cacheService`
- `ingest()` 行 64：`embeddingAdapter.embed(content)` 返回值被丢弃
- `search()` 行 98-100：`ORDER BY created_at DESC LIMIT N`

**具体修改**：
1. **增加注入** `VectorStoreAdapter vectorStoreAdapter`（即 PgvectorAdapter，通过 `@RequiredArgsConstructor` 构造器注入）
2. **修改 `ingest()`**：
   ```java
   float[] vector = embeddingAdapter.embed(content);  // 不再丢弃
   // 保留原有 mapper.insert(fragment) 写入基础字段
   // 同时调用 vectorStoreAdapter.upsert() 写入向量
   VectorFragment vf = new VectorFragment();
   vf.setId(fragment.getId());
   vf.setContent(content);
   vf.setEmbedding(vector);
   vf.setMetadata(metadata);
   vectorStoreAdapter.upsert(List.of(vf));
   ```
3. **修改 `search()`**：
   ```java
   // 不再使用 mapper.selectList(ORDER BY created_at)
   // 改为调用 vectorStoreAdapter.search() 做向量检索
   float[] queryVector = embeddingAdapter.embed(query);
   Map<String, Object> filters = new HashMap<>();
   filters.put("tenantId", tenantId);
   List<VectorFragment> fragments = vectorStoreAdapter.search(queryVector, topK, filters);
   // 将 VectorFragment 转换为 SemanticFragment 返回
   ```

**验证**：
- 写入一条知识后，用 psql 确认 `embedding` 列非 NULL
- `search("数据库连接")` 能返回语义相关的片段，而非按时间排序的结果

---

### 步骤 4：UserContext 增加 tenantId

**目标**：`UserContext` 无 `tenantId` 字段和 `currentTenantId()` 方法，导致 Wenshi 路径无法获取租户 ID。

**涉及文件**：
- `gewu-common/src/main/java/com/gewu/common/context/UserContext.java`

**具体修改**：
1. 增加 `tenantId` 字段（`ThreadLocal<String>`）
2. 增加 `currentTenantId()` 静态方法
3. 增加 `setTenantId(String)` 方法（在认证过滤器中调用）
4. 默认值：如果未设置，返回 `"default"`（当前系统为单租户，RLS 已就绪但未启用多租户）

**验证**：编译通过，现有 `UserContextTest` 仍通过。

---

### 步骤 5：AiChatController 传递 tenantId

**目标**：`chatViaWenshi` 和 `chatStreamViaWenshi` 构建 `WenshiReasoningRequest` 时未设置 `tenantId`。

**涉及文件**：
- `gewu-interface/src/main/java/com/gewu/interfaceapi/controller/AiChatController.java`

**当前代码**：
- `chatViaWenshi` 行 170-176：builder 链无 `.tenantId(...)`
- `chatStreamViaWenshi` 行 214-220：同上

**具体修改**：在两个 builder 链中增加：
```java
.tenantId(UserContext.currentTenantId())  // 行 175 之后
```

**验证**：用 codebase-memory 确认 `WenshiReasoningEngine` 的 `request.getTenantId()` 不再为 null：
```bash
codebase-memory-mcp cli trace_path '{"function_name":"getTenantId","project":"gewu-platform","direction":"callers","depth":2}'
```

---

### 步骤 6：补充核心 AI 代码测试

**目标**：Wenshi 推理/学习层、LLM 客户端、MCP 客户端无任何测试。

**涉及文件**（新建）：
- `gewu-application/src/test/java/com/gewu/application/wenshi/reasoning/SolverRouterTest.java`
  - 测试 `selectStrategy()` 的优先级路由逻辑
  - 测试 `tryKnowledgeLookup()` / `tryRuleMatch()` 的分支
- `gewu-application/src/test/java/com/gewu/application/wenshi/reasoning/PlannerTest.java`
  - 测试 `tryTemplateMatch()` 的关键词匹配
  - 测试 `decomposeViaLlm()` 的回退行为
- `gewu-application/src/test/java/com/gewu/application/wenshi/knowledge/SemanticMemoryServiceTest.java`
  - Mock `EmbeddingAdapter` 和 `VectorStoreAdapter`
  - 测试 `ingest()` 调用了 `vectorStoreAdapter.upsert()`
  - 测试 `search()` 调用了 `vectorStoreAdapter.search()` 而非时间排序
- `gewu-application/src/test/java/com/gewu/application/wenshi/learning/ExperienceExtractorTest.java`
  - 测试 `extract()` 构建的 Experience 字段正确性
- `gewu-application/src/test/java/com/gewu/application/session/ContextCompressorTest.java`
  - 测试截断边界（超长/空/单条消息）

**验证**：`mvn test` 全部通过，测试覆盖 Wenshi 核心路径。

---

## 阶段二：P1 功能补全（3-4 周）

### 步骤 7：WenshiReasoningEngine 实现 TOOL_EXECUTION 策略

**目标**：`executeSingleSubgoal()` 的 `TOOL_EXECUTION` 分支返回占位字符串。

**涉及文件**：
- `gewu-application/src/main/java/com/gewu/application/wenshi/reasoning/WenshiReasoningEngine.java`

**当前代码**（行 335-338）：
```java
case TOOL_EXECUTION:
    log.debug("TOOL_EXECUTION: subgoal={}", subgoal.getDescription());
    return "工具执行: " + subgoal.getDescription();
```

**当前注入缺口**：引擎未注入 `ToolExecutionService`（行 37-64 无此依赖）。

**具体修改**：
1. 增加 `@RequiredArgsConstructor` 字段：`ToolExecutionService toolExecutionService`、`AgentToolMapper agentToolMapper`
2. 实现 `TOOL_EXECUTION` case：
   ```java
   case TOOL_EXECUTION:
       // 从 subgoal 提取工具名和参数
       String toolName = extractToolName(subgoal);
       String arguments = extractArguments(subgoal);
       // 构建工具上下文
       ToolContext ctx = ToolContext.builder()
           .userId(request.getUserId())
           .tenantId(request.getTenantId())
           .sandboxEnabled(true)
           .build();
       ToolResult result = toolExecutionService.executeTool(toolName, arguments, ctx);
       return result.isSuccess() ? result.getOutput() : "工具执行失败: " + result.getError();
   ```
3. 增加 `extractToolName()` / `extractArguments()` 辅助方法（从 subgoal description 或 metadata 解析）

**依赖**：步骤 5（tenantId 可用）

**验证**：单元测试 mock `ToolExecutionService`，验证 TOOL_EXECUTION 调用了 `executeTool()`。

---

### 步骤 8：Experience 实体增加 embedding 字段 + 向量查询

**目标**：`Experience` 实体无 `embedding` 字段，`ExperienceMapper` 无向量查询方法。DB 表已有 `embedding vector(384)` 列。

**涉及文件**：
- `gewu-domain/src/main/java/com/gewu/domain/wenshi/learning/Experience.java`（行 13-22）
- `gewu-infrastructure/src/main/java/com/gewu/infrastructure/mapper/wenshi/ExperienceMapper.java`

**具体修改**：
1. `Experience` 实体增加 `@TableField(exist = false) private float[] embedding;`（不参与 MyBatis-Plus CRUD，通过自定义 SQL 操作）
2. `ExperienceMapper` 增加自定义方法：
   ```java
   @Select("SELECT *, embedding <=> CAST(#{queryVector} AS vector) AS distance " +
           "FROM wenshi_experience WHERE tenant_id = #{tenantId} AND deleted = 0 " +
           "ORDER BY embedding <=> CAST(#{queryVector} AS vector) LIMIT #{topK}")
   List<Experience> searchByVector(@Param("queryVector") String queryVectorHex,
                                    @Param("tenantId") String tenantId,
                                    @Param("topK") int topK);
   ```
   > 注意：pgvector 的向量参数需要以字符串形式传入 `"[1.0,2.0,...]"` 并 `CAST` 为 vector 类型。需验证 MyBatis 参数绑定方式。

**验证**：写入带 embedding 的 Experience，调用 `searchByVector` 返回语义相似结果。

---

### 步骤 9：SolverRouter 实现 tryExperienceReuse

**目标**：`tryExperienceReuse()` 总返回 null，`EXPERIENCE_REUSE` 策略永不被选中。

**涉及文件**：
- `gewu-application/src/main/java/com/gewu/application/wenshi/reasoning/SolverRouter.java`

**当前代码**（行 92-95）：`tryExperienceReuse` 无注入字段，返回 null。

**具体修改**：
1. 改为 `@RequiredArgsConstructor`，注入 `ExperienceMapper experienceMapper`、`EmbeddingAdapter embeddingAdapter`
2. 实现 `tryExperienceReuse()`：
   ```java
   private Strategy tryExperienceReuse(SubgoalNode subgoal, WenshiReasoningRequest request) {
       float[] queryVector = embeddingAdapter.embed(subgoal.getDescription());
       String vectorStr = vectorToString(queryVector);
       List<Experience> matches = experienceMapper.searchByVector(
           vectorStr, request.getTenantId(), 1);
       if (!matches.isEmpty() && matches.get(0).getScore().compareTo(BigDecimal.valueOf(0.7)) >= 0) {
           return Strategy.EXPERIENCE_REUSE; // 并将匹配的 Experience 存入上下文供 executeSingleSubgoal 使用
       }
       return null;
   }
   ```

**依赖**：步骤 8（Experience 向量查询可用）、步骤 4（tenantId 可用）

**验证**：单元测试：写入高分 Experience 后，`selectStrategy` 返回 `EXPERIENCE_REUSE`。

---

### 步骤 10：WenshiReasoningEngine 实现 EXPERIENCE_REUSE 策略

**目标**：`executeSingleSubgoal()` 的 `EXPERIENCE_REUSE` 分支返回占位字符串。

**涉及文件**：
- `gewu-application/src/main/java/com/gewu/application/wenshi/reasoning/WenshiReasoningEngine.java`

**当前代码**（行 339-342）：返回 `"经验复用: " + subgoal.getDescription()`

**具体修改**：实现复用逻辑：
```java
case EXPERIENCE_REUSE:
    // 从 SolverRouter 传递的上下文中获取匹配的 Experience
    Experience matched = (Experience) subgoal.getMetadata().get("matchedExperience");
    if (matched != null && matched.getStrategy() != null) {
        // 增加命中计数
        experienceMapper.incrementHitCount(matched.getId());
        // 返回经验中的解决方案
        return matched.getStrategy();
    }
    // 回退到 LLM
    return callLlm(subgoal, request, routing);
```

**依赖**：步骤 9（SolverRouter 能找到匹配 Experience）

**验证**：单元测试 mock ExperienceMapper，验证 EXPERIENCE_REUSE 返回经验策略而非占位字符串。

---

### 步骤 11：Planner 实现 decomposeViaLlm

**目标**：`decomposeViaLlm()` 未调用 LLM，仅包装为单子目标。

**涉及文件**：
- `gewu-application/src/main/java/com/gewu/application/wenshi/reasoning/Planner.java`

**当前代码**（行 104-109）：无注入字段，返回单元素 List。

**具体修改**：
1. 改为 `@RequiredArgsConstructor`，注入 `LlmClientFactory llmClientFactory`、`@Value` provider/model
2. 实现 `decomposeViaLlm()`：
   ```java
   private List<SubgoalNode> decomposeViaLlm(String task) {
       String prompt = "将以下任务分解为2-4个子目标，每个子目标标注策略类型(KNOWLEDGE_LOOKUP/TOOL_EXECUTION/LLM_REASONING)。"
           + "以JSON数组返回：[{\"description\":\"...\",\"strategy\":\"...\"}]\n任务：" + task;
       LlmRequest req = LlmRequest.builder().messages(List.of(
           Message.system("你是任务分解专家"), Message.user(prompt)))
           .temperature(0.3).maxTokens(1024).build();
       LlmResponse resp = llmClientFactory.getClient(provider).chat(req);
       return parseSubgoals(resp.getContent()); // 解析 JSON 为 SubgoalNode 列表
   }
   ```
3. 增加 `parseSubgoals(String json)` 方法（Jackson 解析 + 容错）

**验证**：单元测试 mock LlmClient，验证返回多个子目标。

---

### 步骤 12：修复 ExperienceExtractor 持久化 + 类型不匹配

**目标**：`extract()` 构建 Experience 但不持久化（mapper 未调用 insert）；嵌套 `ReasoningTrace` 类型与域实体不同。

**涉及文件**：
- `gewu-application/src/main/java/com/gewu/application/wenshi/learning/ExperienceExtractor.java`

**当前代码**：
- 行 30：`experienceMapper` 已注入但 `extract()` 未调用 `insert()`
- 行 129-142：自定义嵌套 `ReasoningTrace` 类，与 `com.gewu.domain.wenshi.learning.ReasoningTrace` 不同

**具体修改**：
1. 删除嵌套 `ReasoningTrace` 类（行 129-142），改用域实体 `com.gewu.domain.wenshi.learning.ReasoningTrace`
2. 修改 `extract()` 签名接收域实体 `ReasoningTrace`
3. 在 `extract()` 末尾增加持久化：
   ```java
   experienceMapper.insert(experience);
   return experience;
   ```
4. 增加 embedding 计算与写入：`experience.setEmbedding(embeddingAdapter.embed(experience.getScenario()))`

**依赖**：步骤 8（Experience 实体有 embedding 字段）

**验证**：单元测试验证 `extract()` 调用了 `experienceMapper.insert()`。

---

### 步骤 13：修复 ReflectionAgent Long/String bug + LLM 集成

**目标**：`reflect(Long experienceId)` 传 Long 但 `Experience.id` 是 String；LLM 兜底返回硬编码字符串。

**涉及文件**：
- `gewu-application/src/main/java/com/gewu/application/wenshi/learning/ReflectionAgent.java`

**当前代码**：
- 行 30：注入 `ExperienceMapper`，无 LLM 客户端
- 行 44：`reflect(Long experienceId)` —— Long 类型不匹配
- 行 65-70：LLM 兜底返回硬编码 `"未知失败模式，需要 LLM 深度分析"`

**具体修改**：
1. 修改 `reflect()` 签名：`Long experienceId` -> `String experienceId`
2. 注入 `LlmClientFactory llmClientFactory`
3. 实现 LLM 兜底：
   ```java
   String prompt = "分析以下失败经验的根因和改进建议，以JSON返回：\n"
       + "场景：" + experience.getScenario() + "\n策略：" + experience.getStrategy();
   LlmResponse resp = llmClientFactory.getClient(provider).chat(req);
   // 解析 JSON 为 rootCause + improvement
   ```

**验证**：单元测试 mock LlmClient，验证 LLM 兜底路径调用了 `chat()`。

---

### 步骤 14：学习层集成到 WenshiReasoningEngine 主流程

**目标**：学习层组件（ExperienceExtractor、ReflectionAgent、SkillEvolver）零调用者。

**涉及文件**：
- `gewu-application/src/main/java/com/gewu/application/wenshi/reasoning/WenshiReasoningEngine.java`

**具体修改**：
1. 注入 `ExperienceExtractor experienceExtractor`、`ReflectionAgent reflectionAgent`、`SkillEvolver skillEvolver`
2. 在 `reason()` 和 `reasonStream()` 的 `saveTrace()` 之后，增加异步学习触发：
   ```java
   CompletableFuture.runAsync(() -> {
       try {
           Experience exp = experienceExtractor.extract(trace);
           if ("FAIL".equals(exp.getOutcome()) || exp.getScore().compareTo(BigDecimal.valueOf(0.6)) < 0) {
               reflectionAgent.reflect(exp.getId());
           }
           // 定期触发技能进化（可改为定时任务）
       } catch (Exception e) {
           log.warn("学习层异步处理失败", e);
       }
   });
   ```
3. 修复 `reason()` 中未使用的 `injection`（行 96）和 `criticResult`（行 102）——将 injection 注入 systemPrompt，将 criticResult 写入 trace

**依赖**：步骤 12、步骤 13（ExperienceExtractor 和 ReflectionAgent 修复完成）

**验证**：执行一次推理后，确认 `wenshi_experience` 表新增记录；`wenshi_reasoning_trace` 表记录完整。

---

### 步骤 15：ContextCompressor 升级为 LLM 摘要

**目标**：`compress()` 为纯截断，非语义摘要。

**涉及文件**：
- `gewu-application/src/main/java/com/gewu/application/session/ContextCompressor.java`

**当前代码**（行 12-38）：`MAX_COMPRESSED_LENGTH=4000`，字符串拼接截断。

**具体修改**：
1. 注入 `LlmClientFactory llmClientFactory`（改为 `@Component` + `@RequiredArgsConstructor`）
2. 修改 `compress()`：
   ```java
   public String compress(List<MessageView> messages) {
       String transcript = messages.stream()
           .map(m -> "[" + m.role() + "] " + m.content())
           .collect(Collectors.joining("\n"));
       // 缓存检查（基于 transcript hash）
       String cacheKey = "compress:" + transcript.hashCode();
       String cached = cacheService.get(cacheKey);
       if (cached != null) return cached;
       // LLM 摘要
       String prompt = "将以下对话历史压缩为不超过800 token的摘要，保留关键实体、数据、决策和未解决问题：\n" + transcript;
       String summary = llmClientFactory.getClient(provider).chat(req).getContent();
       cacheService.set(cacheKey, summary, Duration.ofHours(2));
       return summary;
   }
   ```
3. 增加配置开关 `gewu.session.context-compress-mode = llm | truncate`（默认 llm，可回退）

**验证**：单元测试 mock LlmClient，验证调用 `chat()`；集成测试验证摘要缓存命中。

---

## 阶段三：P2 前端与增强（3-4 周，可与阶段二并行）

### 步骤 16：前端管理页面接入真实 API

**目标**：6 个管理页面全部为 Mock（硬编码数据、按钮无响应）。

**涉及文件**（`gewu-web/src/components/pages/`）：
- `AgentManagePage.tsx` —— 接入 `/v1/agents` CRUD
- `SkillLibraryPage.tsx` —— 接入 `/v1/skills` 列表
- `McpServerPage.tsx` —— 接入 `/v1/mcp-servers` 管理
- `MySkillsPage.tsx` —— 接入 `/v1/skills/mine`
- `MyAgentsPage.tsx` —— 接入 `/v1/agents/mine`
- `AgentMarketPage.tsx` —— 接入 `/v1/agents/market`

**具体修改**：每个页面：
1. 删除硬编码 `const xxx = [...]` 数组
2. 用 `useEffect` + `fetch` / API 客户端加载数据
3. 按钮绑定真实 API 调用（创建/编辑/删除/启动/停止）
4. 后端 API 已存在于 `api.ts` 端点定义中，仅需前端接入

**验证**：手动验证每个页面数据来自后端，操作有实际效果。

---

### 步骤 17：清理死代码

**涉及文件**：
- `gewu-web/src/components/pages/useChatStream.ts` —— 删除（零导入者）
- `gewu-web/src/components/pages/chatData.ts` —— 删除（零导入者）
- `gewu-web/src/components/pages/PrototypePage.tsx` —— 检查是否仍需保留

**验证**：`grep -r "useChatStream\|chatData" gewu-web/src/` 无结果；前端编译通过。

---

### 步骤 18-20（概述，执行时细化）

- **步骤 18**：多 Agent 协作 —— 实现 Planner/Solver/Critic 独立 Agent 通信
- **步骤 19**：评估体系 —— `EvaluationRunner` 替换 `Math.random()` 为真实评估
- **步骤 20**：多级缓存 —— 本地缓存（Caffeine）+ Redis 双层

---

## 阶段四：P3 远期规划（持续，按需排期）

- Agent 执行监控与告警（Prometheus 自定义指标）
- Token 预算与成本控制
- Agent 模式 A/B 测试框架
- 本地模型支持（Ollama/llama.cpp）

---

## 依赖关系图

```
步骤1 (Schema) ──┬─> 步骤2 (实体embedding) ──> 步骤3 (记忆连通) ──┐
                 │                                                  ├─> 步骤6 (测试)
步骤4 (tenantId) ─┴─> 步骤5 (Controller传参) ──────────────────────┘
                                                                        │
步骤8 (Experience embedding) ──> 步骤9 (Router) ──> 步骤10 (EXPERIENCE_REUSE)
                        │
步骤12 (Extractor修复) ─┴─> 步骤13 (Reflection修复) ──> 步骤14 (学习层集成)
                                                                        │
步骤7 (TOOL_EXECUTION) ─────────────────────────────────────────────────┤
步骤11 (Planner LLM) ──────────────────────────────────────────────────┤
步骤15 (上下文压缩) ───────────────────────────────────────────────────┘
                                                                        │
步骤16-17 (前端) ───────────────────────────────────────────────────── 可并行
```

## 执行检查清单

每个步骤完成后：
1. [ ] `mvn compile` 编译通过
2. [ ] `mvn test` 相关测试通过
3. [ ] 用 codebase-memory `trace_path` 确认无意外影响
4. [ ] 提交 Git（每步骤一个 commit）
5. [ ] 更新本计划中的完成状态

## 新发现的附加 Bug（融入对应步骤）

| Bug | 融入步骤 | 说明 |
|-----|----------|------|
| Schema 缺列 | 步骤 1 | wenshi 表缺 created_by/updated_by/deleted |
| PgvectorAdapter.upsert SQL 引用不存在的列 | 步骤 1+3 | 补列后 SQL 可正常运行 |
| ExperienceExtractor 类型不匹配 | 步骤 12 | 嵌套 ReasoningTrace 改用域实体 |
| ReflectionAgent Long/String bug | 步骤 13 | reflect(Long) 改为 reflect(String) |
| reason() 未使用 injection/criticResult | 步骤 14 | 注入 injection 到 prompt，写入 criticResult 到 trace |
| saveTrace() 随机 taskId | 步骤 14 | 保留真实 task id |
| determineOutcome() 永不返回 FAIL | 步骤 12 | 增加 FAIL 判定逻辑（score < 0.4） |
