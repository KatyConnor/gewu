# Wenshi（格物知行）—— 开发计划 v1.0

> 版本：v1.0  ·  日期：2026-07-16  ·  状态：执行中
>
> 关联文档：
> - `WENSHI-ARCHITECTURE-v3.md`（架构方案）
> - `WENSHI-SYSTEM-DESIGN.md`（系统设计）

---

## 开发总则

### 原则
1. **阶段 A 不改变现有功能** —— Wenshi 模块独立开发，通过配置开关控制
2. **代码风格与现有项目一致** —— Lombok + MyBatis-Plus + Spring Boot
3. **无注释** —— 代码自解释，方法名/变量名表达意图
4. **编译验证** —— 每个任务完成后 `mvn compile` 通过

### 阶段 A 目标（第 1-4 周）
- 知识层基础设施（pgvector + embedding + 四类记忆存储）
- 记忆路由基础实现
- 现有系统数据导入（session_message → 情景记忆，AgentTool → 程序性记忆）

---

## 阶段 A 任务拆解

### 第 1 周：基础设施 + 适配层

#### 任务 A1.1：pgvector DDL 迁移（0.5 天）

**目标**：创建 Wenshi 知识层所需的数据库表

**产出文件**：
- `gewu-interface/src/main/resources/db/migration/V6__wenshi_knowledge_layer.sql`

**表清单**：
- `wenshi_semantic_fragment`（语义记忆片段）
- `wenshi_episodic_event`（情景事件）
- `wenshi_procedural_memory`（程序性记忆）
- `wenshi_experience`（经验库）
- `wenshi_user_profile`（用户画像）
- `wenshi_reasoning_trace`（推理轨迹）

**验证**：DDL 文件语法正确，索引创建成功

---

#### 任务 A1.2：EmbeddingAdapter 接口 + BGE-small 适配器（1 天）

**目标**：定义嵌入适配器接口，实现 BGE-small 本地推理

**产出文件**：
- `gewu-infrastructure/src/main/java/com/gewu/infrastructure/wenshi/adapter/EmbeddingAdapter.java`
- `gewu-infrastructure/src/main/java/com/gewu/infrastructure/wenshi/adapter/BgeSmallAdapter.java`
- `gewu-infrastructure/src/main/java/com/gewu/infrastructure/wenshi/adapter/LlmNativeEmbeddingAdapter.java`

**EmbeddingAdapter 接口**：
```java
public interface EmbeddingAdapter {
    float[] embed(String text);
    float[][] embedBatch(List<String> texts);
    int getDimension();
}
```

**BgeSmallAdapter**：
- 使用 ONNX Runtime 加载 BGE-small 模型
- 输入文本 → 输出 384 维向量
- 模型路径从配置读取

**LlmNativeEmbeddingAdapter**：
- 包装现有 LlmClient 的 embedding 能力（如有）
- 作为快速验证的备选

**验证**：`mvn compile` 通过，单元测试 embedding 输出维度正确

---

#### 任务 A1.3：VectorStoreAdapter 接口 + pgvector 适配器（1 天）

**目标**：定义向量库适配器接口，实现 pgvector 存储

**产出文件**：
- `gewu-infrastructure/src/main/java/com/gewu/infrastructure/wenshi/adapter/VectorStoreAdapter.java`
- `gewu-infrastructure/src/main/java/com/gewu/infrastructure/wenshi/adapter/PgvectorAdapter.java`

**VectorStoreAdapter 接口**：
```java
public interface VectorStoreAdapter {
    void upsert(List<VectorFragment> fragments);
    List<VectorFragment> search(float[] query, int topK, Map<String, Object> filters);
    void delete(List<String> ids);
}
```

**PgvectorAdapter**：
- 使用 JDBC 直接操作 pgvector
- 支持 cosine 距离检索
- 支持元数据过滤（tenant_id, memory_type 等）

**验证**：`mvn compile` 通过，向量写入 + 检索功能正确

---

#### 任务 A1.4：领域实体 + Mapper（1 天）

**目标**：创建 Wenshi 领域实体和 MyBatis-Plus Mapper

**产出文件**：
- `gewu-domain/src/main/java/com/gewu/domain/wenshi/knowledge/SemanticFragment.java`
- `gewu-domain/src/main/java/com/gewu/domain/wenshi/knowledge/EpisodicEvent.java`
- `gewu-domain/src/main/java/com/gewu/domain/wenshi/knowledge/ProceduralMemory.java`
- `gewu-domain/src/main/java/com/gewu/domain/wenshi/learning/Experience.java`
- `gewu-domain/src/main/java/com/gewu/domain/wenshi/learning/Skill.java`
- `gewu-domain/src/main/java/com/gewu/domain/wenshi/knowledge/UserProfile.java`
- `gewu-infrastructure/src/main/java/com/gewu/infrastructure/mapper/wenshi/SemanticFragmentMapper.java`
- `gewu-infrastructure/src/main/java/com/gewu/infrastructure/mapper/wenshi/EpisodicEventMapper.java`
- `gewu-infrastructure/src/main/java/com/gewu/infrastructure/mapper/wenshi/ProceduralMemoryMapper.java`
- `gewu-infrastructure/src/main/java/com/gewu/infrastructure/mapper/wenshi/ExperienceMapper.java`
- `gewu-infrastructure/src/main/java/com/gewu/infrastructure/mapper/wenshi/UserProfileMapper.java`

**验证**：`mvn compile` 通过

---

#### 任务 A1.5：WenshiProperties 配置类（0.5 天）

**目标**：创建 Wenshi 配置属性类

**产出文件**：
- `gewu-application/src/main/java/com/gewu/application/wenshi/config/WenshiProperties.java`

**配置项**：
- `wenshi.enabled`：总开关
- `wenshi.routing.chat`：对话路由（wenshi/legacy）
- `wenshi.embedding.adapter`：嵌入适配器选择
- `wenshi.embedding.model-path`：BGE-small 模型路径
- `wenshi.vectorstore.adapter`：向量库适配器选择
- `wenshi.tenant.isolation`：租户隔离模式

**验证**：`mvn compile` 通过，配置正确注入

---

#### 任务 A1.6：WenshiAutoConfiguration（0.5 天）

**目标**：自动装配 Wenshi 组件

**产出文件**：
- `gewu-application/src/main/java/com/gewu/application/wenshi/config/WenshiAutoConfiguration.java`

**逻辑**：
- 读取 `wenshi.enabled` 判断是否启用
- 根据配置选择 EmbeddingAdapter 实现
- 根据配置选择 VectorStoreAdapter 实现
- 注册所有 Wenshi Bean

**验证**：`mvn compile` 通过，Spring 上下文加载成功

---

### 第 2 周：知识层核心

#### 任务 A2.1：SemanticMemoryService（1 天）

**目标**：语义记忆服务 —— 知识写入 + 检索

**产出文件**：
- `gewu-application/src/main/java/com/gewu/application/wenshi/knowledge/SemanticMemoryService.java`

**核心方法**：
- `ingest(content, metadata)`：知识写入（分块 → embedding → 存储）
- `search(query, topK)`：语义检索
- `importFromDocuments(documents)`：文档批量导入

**验证**：知识写入后能语义检索命中

---

#### 任务 A2.2：EpisodicMemoryService（1 天）

**目标**：情景记忆服务 —— 事件记录 + 时序/语义检索

**产出文件**：
- `gewu-application/src/main/java/com/gewu/application/wenshi/knowledge/EpisodicMemoryService.java`

**核心方法**：
- `record(event)`：记录事件
- `queryByTimeRange(range)`：时间范围查询
- `queryBySemantic(query, topK)`：语义检索
- `queryByTimeAndSemantic(query, range, topK)`：时间+语义联合检索

**验证**：历史事件可按时间和语义检索

---

#### 任务 A2.3：ProceduralMemoryService（1 天）

**目标**：程序性记忆服务 —— 工具/SOP/Skill 管理

**产出文件**：
- `gewu-application/src/main/java/com/gewu/application/wenshi/knowledge/ProceduralMemoryService.java`

**核心方法**：
- `registerTool(tool)`：注册工具
- `registerSOP(sop)`：注册 SOP
- `registerSkill(skill)`：注册 Skill
- `findTool(name)`：按名称查找工具
- `findSOP(scenario)`：按场景匹配 SOP
- `findSkill(scenario)`：按场景匹配 Skill

**验证**：工具/SOP/Skill 可注册和检索

---

#### 任务 A2.4：ParametricMemoryService（0.5 天）

**目标**：参数化记忆服务 —— 用户画像 + 行为学习

**产出文件**：
- `gewu-application/src/main/java/com/gewu/application/wenshi/knowledge/ParametricMemoryService.java`

**核心方法**：
- `getProfile(userId)`：获取用户画像
- `setPreference(userId, key, value)`：设置偏好
- `learnFromBehavior(userId, behavior)`：从行为学习

**验证**：用户画像可读写，行为学习自动更新

---

#### 任务 A2.5：MemoryRouter 基础实现（1.5 天）

**目标**：记忆路由 —— 根据任务类型路由到合适的记忆源

**产出文件**：
- `gewu-application/src/main/java/com/gewu/application/wenshi/knowledge/MemoryRouter.java`
- `gewu-application/src/main/java/com/gewu/application/wenshi/knowledge/MemoryRouterImpl.java`

**路由规则**：
- 数据查询 → 语义记忆 + 程序性记忆
- 数据分析 → 语义记忆 + 情景记忆 + 程序性记忆
- 流程执行 → 程序性记忆 + 参数化记忆
- 知识问答 → 语义记忆
- 不确定 → LLM 决定

**验证**：不同任务类型路由到正确的记忆源

---

### 第 3 周：数据导入 + 知识注入

#### 任务 A3.1：历史对话导入管线（1 天）

**目标**：将现有 session_message 导入为情景记忆

**产出文件**：
- `gewu-application/src/main/java/com/gewu/application/wenshi/knowledge/HistoryImportService.java`

**逻辑**：
- 读取 session_message 表
- 按会话分组，转换为 EpisodicEvent
- embedding 化后写入 wenshi_episodic_event
- 支持增量导入（只导入新消息）

**验证**：导入后可按语义检索到历史对话

---

#### 任务 A3.2：工具定义导入（0.5 天）

**目标**：将现有 AgentTool 导入为程序性记忆

**产出文件**：
- 扩展 HistoryImportService 或独立 ToolImportService

**逻辑**：
- 读取 agent_tool 表
- 转换为 ProceduralMemory(type=TOOL)
- 保留与 AgentTool 的映射关系

**验证**：导入后可通过 ProceduralMemoryService 查到工具

---

#### 任务 A3.3：知识注入管线（1.5 天）

**目标**：支持从多种来源写入知识

**产出文件**：
- `gewu-application/src/main/java/com/gewu/application/wenshi/knowledge/KnowledgeIngestionService.java`

**支持来源**：
- 手动录入（API 调用）
- 文档导入（文本分块 → embedding → 存储）
- 对话沉淀（每次对话后自动抽取关键知识）

**文档分块策略**：
- 按段落分块（每块 200-500 字）
- 重叠窗口（相邻块重叠 50 字，避免断句）
- 元数据保留（来源、时间、置信度）

**验证**：文档导入后可语义检索命中

---

#### 任务 A3.4：记忆注入 LLM 实现（1 天）

**目标**：按需注入 —— 先注入摘要，LLM 需要时再展开

**产出文件**：
- `gewu-application/src/main/java/com/gewu/application/wenshi/knowledge/MemoryInjector.java`

**注入逻辑**：
- 第 1 轮：每条记忆只注入前 100 字（摘要）
- 总长度限制 < 2000 token
- LLM 在 prompt 中标记需要展开的记忆 ID
- 第 2 轮：注入完整内容

**验证**：注入后的 prompt 长度可控，信息完整

---

### 第 4 周：集成测试 + 阶段验收

#### 任务 A4.1：知识层集成测试（1.5 天）

**目标**：端到端验证知识层功能

**测试场景**：
1. 写入知识 → 语义检索命中
2. 记录事件 → 时间+语义检索命中
3. 注册工具 → 按名称/场景查找
4. 用户画像 → 读写 + 行为学习
5. 记忆路由 → 不同任务路由到正确记忆源
6. 记忆注入 → prompt 长度可控

---

#### 任务 A4.2：现有系统兼容性验证（1 天）

**目标**：确保 Wenshi 不影响现有功能

**验证项**：
1. `wenshi.enabled=false` 时，所有现有功能正常
2. 现有 AgentExecutionEngine 不受影响
3. 现有 AiChatController 不受影响
4. 现有 session_message 写入不受影响

---

#### 任务 A4.3：阶段 A 验收（0.5 天）

**验收标准**：
- [ ] pgvector 表创建成功
- [ ] BGE-small embedding 输出正确（384 维）
- [ ] 向量检索命中率 > 85%
- [ ] 四类记忆均可写入和检索
- [ ] 历史对话导入成功
- [ ] 工具定义导入成功
- [ ] 记忆路由正确
- [ ] 现有功能不受影响
- [ ] `mvn compile` 通过

---

## 阶段 B 预告（第 5-8 周）

| 周 | 内容 |
|----|------|
| W5 | WenshiReasoningEngine 骨架 + Planner |
| W6 | SolverRouter + Critic |
| W7 | 经验抽取 + 质量评估 + 技能演化 |
| W8 | 对话入口切换 + 集成测试 |

---

## 阶段 C 预告（第 9-12 周）

| 周 | 内容 |
|----|------|
| W9-W10 | 对比实验（4 基线 × 6 场景） |
| W11 | 效果分析 + 调优 |
| W12 | 论文/专利 + PoC 报告 |

---

## 启动执行

本文档批准后，立即启动阶段 A 第 1 周开发。
