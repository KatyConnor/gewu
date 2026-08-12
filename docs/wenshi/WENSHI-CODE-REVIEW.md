# Wenshi 代码审查 + 安全审计报告

> 审查日期：2026-07-16
> 审查范围：Wenshi 全量新增代码（阶段 A + B + C）
> 审查语言：Java 21 + Spring Boot 3.2.5
> 审查方法：静态分析 + OWASP Top 10 + Java 规范

---

## 概要

| 维度 | 结果 |
|------|------|
| 审查语言 | Java |
| 文件数量 | 57 |
| 代码行数 | ~2310 |
| 总体评分 | **6.5/10** |
| 严重问题 | 3 |
| 中等问题 | 8 |
| 建议项 | 7 |

---

## 问题列表

### 🔴 严重（必须修复）

| # | 文件 | 行号 | 问题 | 修复建议 |
|---|------|------|------|---------|
| 001 | `PgvectorAdapter.java` | 37-47 | **N+1 连接泄漏**：循环内每次 `conn.prepareStatement()` 但连接未在 try-with-resources 中，异常时连接不会释放 | 将 Connection 获取移到循环外，或每条 fragment 单独获取+释放连接 |
| 002 | `PgvectorAdapter.java` | 37 | **SQL 注入风险**：`toVectorString()` 输出直接拼入 SQL，虽然用了 `::vector` 强转，但格式字符串可能被注入 | 使用 `PGvector` 辅助类或参数化方式传递向量 |
| 003 | `BgeSmallEmbeddingAdapter.java` | 25-26 | **资源泄漏**：`OrtEnvironment` 和 `OrtSession` 未实现 `Closeable`，应用关闭时不会释放 native 内存 | 实现 `DisposableBean` 或 `@PreDestroy` 释放 session 和 environment |

### 🟡 中等（1 周内修复）

| # | 文件 | 行号 | 问题 | 修复建议 |
|---|------|------|------|---------|
| 004 | `BgeSmallEmbeddingAdapter.java` | 127-136 | **tokenize 不正确**：使用 `charAt(i) % 20000` 作为 token ID，这不是 BGE-small 的 tokenizer，推理结果无意义 | 使用 HuggingFace tokenizer（`tokenizers` Java 库）或 JNI 调用 Python tokenizer |
| 005 | `BgeSmallEmbeddingAdapter.java` | 182-192 | **fallbackEmbed 不安全**：基于 hashCode 的伪随机向量，不同文本可能碰撞，且不可重现 | 至少使用确定性 hash（如 MurmurHash），或直接返回零向量 |
| 006 | `MemoryRouter.java` | 61-63 | **Builder 模式误用**：在 log 中调用 `result.build()` 多次创建临时对象，应该先 build 一次再引用 | 先 `RoutingResult built = result.build()` 再 log |
| 007 | `SkillEvolver.java` | 67-77 | **JSON 手写拼接**：definition 字段使用 StringBuilder 拼 JSON，特殊字符会导致 JSON 解析失败 | 使用 Jackson ObjectMapper 序列化 |
| 008 | `WenshiReasoningEngine.java` | 140-154 | **过宽异常捕获**：`catch (Exception e)` 吞掉了所有异常，包括可能的数据库连接失败 | 分别捕获 SQLException 和 RuntimeException |
| 009 | `WenshiReasoningEngine.java` | 96-138 | **重复路由调用**：`executeSubgoals` 中 KNOWLEDGE_LOOKUP 分支重新调用了 `memoryRouter.route()`，与 reason() 中重复 | 将 routing 结果作为参数传入，避免重复调用 |
| 010 | `PgvectorAdapter.java` | 60-79 | **SQL 拼接风险**：filters 动态拼接 SQL 虽然用了参数化，但 key 是用户输入的，可能被注入不存在的 key | 使用白名单校验 filter key |
| 011 | `PgvectorAdapter.java` | 33 | **缺少事务控制**：upsert 循环中没有事务，部分成功部分失败会导致数据不一致 | 添加 `@Transactional` 或手动事务控制 |

### 🟢 建议（下个迭代）

| # | 文件 | 行号 | 问题 | 修复建议 |
|---|------|------|------|---------|
| 012 | `WenshiReasoningEngine.java` | 82-94 | **魔法字符串**：任务分类使用硬编码中文字符串 | 提取为常量或枚举 |
| 013 | `MemoryRouter.java` | 31-57 | **圈复杂度**：switch 分支较多，建议用策略模式重构 | 将路由规则提取为独立的 RouteStrategy 实现 |
| 014 | `Critic.java` | 14-28 | **Critic 验证不完整**：`tryLlmSelfCheck` 永远返回 passed=true，没有真正调 LLM | 实现真正的 LLM 调用验证 |
| 015 | `BgeSmallEmbeddingAdapter.java` | 67-91 | **双重检查锁**：`loadModel` 的 synchronized + `modelLoaded` 检查是经典 DCL，但 `session` 未声明 volatile | 给 session 加 volatile 或改用静态内部类加载 |
| 016 | `SemanticMemoryService.java` | 45 | **SQL 注入风险**：`.last("LIMIT " + topK)` 直接拼接 LIMIT 参数 | 使用 MyBatis-Plus 的 `.last()` 时确保 topK 是 int 类型（当前安全，但需注释说明） |
| 017 | `ReflectionAgent.java` | 39-52 | **规则匹配不完整**：仅匹配英文关键词，不匹配中文 | 补充中文关键词匹配 |
| 018 | `WenshiDataSourceConfig.java` | 17-28 | **密码硬编码**：datasource 默认密码写在代码中 | 使用环境变量或配置中心 |

---

## 安全风险（OWASP 对照）

| OWASP 编号 | 风险 | 位置 | 等级 |
|-----------|------|------|------|
| A01 权限控制 | 多租户数据未强制过滤 | PgvectorAdapter 依赖调用方传 tenantId | 🟡 中 |
| A03 注入 | 向量字符串直接拼接 SQL | PgvectorAdapter.toVectorString() | 🔴 严重 |
| A03 注入 | filter key 未白名单校验 | PgvectorAdapter.search() | 🟡 中 |
| A07 认证 | 无 API 级权限校验 | WenshiReasoningEngine | 🟢 低 |
| A09 日志监控 | 敏感信息可能打印到日志 | 多处 log.info 打印用户输入 | 🟡 中 |

---

## 性能问题

| # | 问题 | 位置 | 影响 |
|---|------|------|------|
| 1 | **N+1 查询**：upsert 循环中逐条插入 | PgvectorAdapter | 高：1000 条知识需要 1000 次网络往返 |
| 2 | **重复路由**：reason() 和 executeSubgoals() 各调一次 route() | WenshiReasoningEngine | 中：每次请求多一次 DB 查询 |
| 3 | **无缓存**：SemanticMemoryService 每次查询都走 DB | SemanticMemoryService | 中：高频查询应加 Redis 缓存 |
| 4 | **embedBatch 串行**：逐条调用 embed | BgeSmallEmbeddingAdapter | 中：批量场景应并行或一次性推理 |
| 5 | **连接池未调优**：pgvector HikariCP 默认配置 | WenshiDataSourceConfig | 低：生产环境需调优 |

---

## 架构合规检查

| 维度 | 评分 | 说明 |
|------|------|------|
| 分层架构 | ✅ 良好 | Controller → Service → Repository 依赖方向正确 |
| 依赖注入 | ✅ 良好 | 全量使用构造器注入 |
| SOLID 原则 | ⚠️ 部分符合 | MemoryRouter 承担了过多路由职责 |
| DRY 原则 | ⚠️ 部分符合 | 任务分类逻辑在多处重复 |
| 接口隔离 | ✅ 良好 | Adapter 接口设计合理 |
| 异常处理 | ⚠️ 需改进 | 多处过宽异常捕获 |

---

## 最佳实践建议

### 1. PgvectorAdapter 重写为批量操作

```java
// 当前：逐条插入
for (VectorFragment fragment : fragments) {
    ps.executeUpdate();
}

// 建议：使用 COPY 或批量 INSERT
String sql = "INSERT INTO ... VALUES (?,?,?,...),(?,?,?,...),...";
```

### 2. BGE-small tokenizer 替换

```java
// 当前：charAt % 20000（不正确）
tokens[i + 1] = text.charAt(i) % 20000 + 1;

// 建议：使用 HuggingFace tokenizers Java 绑定
// 或调用 Python 服务做 tokenize
```

### 3. 多租户安全加固

```java
// 当前：依赖调用方传 tenantId
// 建议：在 DataSource 层强制设置 row-level security
// 或在所有查询中自动注入当前用户的 tenantId
```

### 4. 资源生命周期管理

```java
@PreDestroy
public void cleanup() {
    if (session != null) {
        try { session.close(); } catch (OrtException e) { /* ignore */ }
    }
    if (environment != null) {
        environment.close();
    }
}
```

---

## 修复优先级

### 立即修复（阻塞上线）
1. PgvectorAdapter 连接泄漏（#001）
2. PgvectorAdapter SQL 注入（#002）
3. BGE-small native 内存泄漏（#003）

### 1 周内修复
4. BGE-small tokenizer 不正确（#004）
5. PgvectorAdapter 事务控制（#011）
6. WenshiReasoningEngine 过宽异常捕获（#008）
7. SkillEvolver JSON 手写拼接（#007）

### 下个迭代
8. 任务分类常量提取
9. MemoryRouter 策略模式重构
10. Critic 真正的 LLM 验证
11. 性能优化（批量 + 缓存）

---

## 总结

Wenshi 架构设计合理，三层分离清晰，适配层接口设计良好。
**主要风险集中在基础设施层**：pgvector 的连接管理和 BGE-small 的推理正确性。
建议在上线前至少修复 3 个严重问题。
