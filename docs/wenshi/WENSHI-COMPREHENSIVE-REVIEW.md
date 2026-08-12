# 格物平台 —— 代码审查 + 安全审计 + 性能分析报告

> 分析方法：Codebase Memory 知识图谱 + 静态代码分析 + OWASP Top 10
> 分析日期：2026-07-17
> 分析范围：全量 305 个 Java 文件，30,505 个节点，39,236 条边

---

## 1. 概要

| 维度 | 结果 |
|------|------|
| 总代码文件 | 305 |
| 知识图谱节点 | 30,505 |
| 方法数 | 770 |
| 函数数 | 443 |
| 类数 | 451 |
| Route 数 | 76 |
| **总体评分** | **6.8/10** |
| 🔴 严重问题 | 5 |
| 🟡 中等问题 | 12 |
| 🟢 建议项 | 8 |

---

## 2. 复杂度热点（全项目 Top 15）

| 排名 | 方法 | 复杂度 | 认知负荷 | 行数 | 层级 |
|------|------|--------|---------|------|------|
| 1 | `QwenClient.buildRequestBody` | 16 | 32 | 67 | infrastructure |
| 2 | `ToolSchemaValidator.validateNode` | 15 | 49 | 44 | application |
| 3 | `DeepSeekClient.buildRequestBody` | 15 | 31 | 60 | infrastructure |
| 4 | `AgentToolService.updateTool` | 12 | 12 | 22 | application |
| 5 | `SandboxService.createSandbox` | 9 | 16 | 47 | sandbox |
| 6 | `ToolExecutionService.executeTool` | 9 | 13 | 54 | application |
| 7 | `WorkflowInstanceService.findNextNode` | 8 | 14 | 30 | application |
| 8 | `AgentService.updateAgent` | 8 | 8 | 19 | application |
| 9 | `ProjectService.updateProject` | 8 | 8 | 17 | application |
| 10 | `ToolExecutionService.executeInSandbox` | 8 | 13 | 50 | application |
| 11 | `WenshiReasoningEngine.executeSubgoals` | 8 | 22 | 41 | wenshi |
| 12 | `Ulid.decodeChar` | 7 | 16 | 11 | common |
| 13 | `QwenClient.chatStream` | 7 | 18 | 39 | infrastructure |
| 14 | `QwenClient.parseStreamChunk` | 7 | 16 | 45 | infrastructure |
| 15 | `DeepSeekClient.chatStream` | 7 | 18 | 39 | infrastructure |

**关键发现**：
- `ToolSchemaValidator.validateNode` 认知负荷 49（全项目最高），需要拆分
- `WenshiReasoningEngine.executeSubgoals` 认知负荷 22，是 Wenshi 最复杂方法
- LLM 客户端的 `buildRequestBody` 复杂度高，因为要处理多种消息格式

---

## 3. Wenshi 模块专项分析

### 3.1 复杂度分布

| 方法 | 复杂度 | 认知 | 行数 | 风险 |
|------|--------|------|------|------|
| `WenshiReasoningEngine.executeSubgoals` | 8 | 22 | 41 | 高 |
| `KnowledgeIngestionService.splitIntoChunks` | 7 | 12 | 37 | 中 |
| `EvaluationRunner.simulateScore` | 7 | 12 | 10 | 中 |
| `MemoryInjector.prepareInjection` | 6 | 9 | 33 | 中 |
| `MemoryInjector.expandMemory` | 6 | 12 | 17 | 中 |
| `PgvectorAdapter.search` | 6 | 9 | 45 | 中 |
| `BgeSmallEmbeddingAdapter.meanPooling` | 6 | 13 | 20 | 中 |
| `MemoryRouter.route` | 6 | 11 | 43 | 中 |

### 3.2 Wenshi 架构合规

| 维度 | 评分 | 说明 |
|------|------|------|
| 分层架构 | ✅ 优秀 | Adapter → Knowledge → Reasoning → Learning 四层清晰 |
| 接口隔离 | ✅ 优秀 | EmbeddingAdapter / VectorStoreAdapter 接口最小化 |
| 依赖方向 | ✅ 正确 | 上层依赖接口，下层实现接口 |
| 适配层设计 | ✅ 优秀 | 支持 bge-small / llm-native / pgvector 可切换 |
| 异常处理 | ⚠️ 需改进 | 部分方法缺少异常处理 |
| 资源管理 | ✅ 已修复 | @PreDestroy 释放 ONNX 资源 |

---

## 4. 安全审计（OWASP Top 10）

### 4.1 安全评分

| OWASP 类别 | 等级 | 状态 |
|-----------|------|------|
| A01 权限控制 | 🟡 中危 | 多租户依赖应用层过滤，DB 层无 RLS |
| A02 加密 | 🟢 安全 | SM3 哈希 + SM2 国密 + JWT HS256 |
| A03 注入 | 🟡 中危 | pgvector filter key 已加固，向量拼接仍有风险 |
| A04 不安全设计 | 🟢 安全 | 架构分层合理 |
| A05 配置错误 | 🟡 中危 | 默认密码硬编码在配置中 |
| A06 过时组件 | 🟢 安全 | Spring Boot 3.2.5 较新 |
| A07 认证 | 🟢 安全 | JWT + Refresh Token + 登录失败锁定 |
| A08 数据完整性 | 🟢 安全 | HTTPS + 国密 TLS |
| A09 日志监控 | 🟡 中危 | 用户输入可能打印到日志 |
| A10 SSRF | 🟠 高危 | HTTP 工具执行未做 URL 白名单校验 |

### 4.2 安全热点详情

| # | 等级 | 类型 | 位置 | 描述 | 修复建议 |
|---|------|------|------|------|---------|
| S01 | 🟠 高危 | SSRF | `ToolExecutionService.executeViaHttp` | 工具 endpoint 未做白名单校验，可访问内网地址 | 增加 URL 白名单 + 内网 IP 拦截 |
| S02 | 🟡 中危 | 注入 | `PgvectorAdapter.upsert` | 向量字符串直接拼接 SQL | 使用 PGvector 类型辅助类 |
| S03 | 🟡 中危 | 配置 | `WenshiDataSourceConfig` | 默认密码硬编码 | 使用环境变量强制覆盖 |
| S04 | 🟡 中危 | 日志 | `AuthService.login` | 登录失败时打印用户名 | 对用户名脱敏 |
| S05 | 🟡 中危 | 租户隔离 | `PgvectorAdapter.search` | 依赖调用方传 tenantId | DB 层增加 RLS 策略 |
| S06 | 🟢 低危 | 认证 | `AiChatController` | Wenshi 入口无权限注解 | 添加 `@PreAuthorize` |

### 4.3 认证授权分析

**认证链路**：
```
请求 → JwtAuthenticationFilter → JwtUtil.parseToken → SecurityContext
     → SecurityConfig 白名单校验
     → PermissionEvaluationService 工具级权限
```

**安全措施**：
- ✅ JWT HS256 签名
- ✅ Access Token 30min + Refresh Token 7天
- ✅ 登录失败 5 次锁定账户
- ✅ XSS 过滤（XssFilter + XssRequestWrapper）
- ✅ CSRF 安全头
- ✅ 国密算法（SM2/SM3/SM4）
- ⚠️ 无 Refresh Token 轮换机制
- ⚠️ 无 API 速率限制（有注解未实现）

---

## 5. 性能分析

### 5.1 性能热点

| # | 问题 | 位置 | 影响 | 修复建议 |
|---|------|------|------|---------|
| P01 | N+1 插入 | `PgvectorAdapter.upsert` | 高 | 已修复为 batch，可进一步优化为 COPY |
| P02 | 重复路由调用 | `WenshiReasoningEngine` | 中 | 已修复，routing 参数传递 |
| P03 | 无缓存层 | `SemanticMemoryService` | 中 | 高频查询加 Redis 缓存 |
| P04 | 串行批量推理 | `BgeSmallEmbeddingAdapter.embedBatch` | 中 | 并行或一次性 batch 推理 |
| P05 | 大对象序列化 | `SkillEvolver.generateDefinition` | 低 | 已修复为 Jackson 序列化 |
| P06 | 连接池未调优 | `WenshiDataSourceConfig` | 低 | 生产环境调优 HikariCP |

### 5.2 资源管理

| 资源 | 管理方式 | 状态 |
|------|---------|------|
| ONNX Session | @PreDestroy 释放 | ✅ 已修复 |
| HTTP Client | 静态单例 | ✅ 安全 |
| DB 连接 | HikariCP 连接池 | ✅ 安全 |
| Flux 流 | doOnComplete 清理 | ✅ 安全 |

---

## 6. 代码质量分析

### 6.1 代码规范遵循

| 规范 | 遵循度 | 说明 |
|------|--------|------|
| 命名规范 | 95% | PascalCase 类 / camelCase 方法 / UPPER_SNAKE 常量 |
| 函数长度 | 85% | 个别方法 > 40 行（executeSubgoals 41 行） |
| 圈复杂度 | 80% | 8 个方法复杂度 >= 8 |
| 异常处理 | 75% | 部分方法缺少异常处理 |
| 注释 | 60% | 关键方法缺少 Javadoc |
| 重复代码 | 85% | 任务分类逻辑在多处重复 |

### 6.2 技术债务

| 类型 | 数量 | 严重度 |
|------|------|--------|
| 空实现 | 5 | 高 |
| 硬编码 | 8 | 中 |
| 过宽异常捕获 | 3 | 中 |
| 魔法字符串 | 12 | 低 |
| 过长函数 | 6 | 低 |

---

## 7. 模块健康度评分

| 模块 | 评分 | 最强点 | 最弱点 |
|------|------|--------|--------|
| **gewu-common** | 8.5/10 | 工具类设计清晰 | 注释不足 |
| **gewu-domain** | 9/10 | 实体设计规范 | 缺少值对象 |
| **gewu-infrastructure** | 7/10 | 适配层设计优秀 | LLM 客户端复杂度高 |
| **gewu-application** | 7.5/10 | 服务编排清晰 | 部分方法复杂度偏高 |
| **gewu-interface** | 8/10 | 安全配置完善 | 部分 Controller 缺少权限注解 |
| **gewu-gateway** | 7.5/10 | 限流熔断完整 | 路由配置需扩展 |
| **gewu-sandbox** | 7/10 | 隔离设计合理 | 资源限制需加强 |
| **gewu-wenshi** | 6.5/10 | 架构设计合理 | 空实现多、tokenizer 不正确 |

---

## 8. 修复优先级

### 🔴 立即修复（阻塞上线）

| # | 事项 | 影响 |
|---|------|------|
| 1 | SSRF 防护：executeViaHttp 增加 URL 白名单 | 内网安全 |
| 2 | Refresh Token 轮换机制 | 令牌安全 |
| 3 | BGE-small tokenizer 替换 | 推理正确性 |
| 4 | pgvector DB 层 RLS 策略 | 多租户隔离 |

### 🟡 1 周内修复

| # | 事项 | 影响 |
|---|------|------|
| 5 | `executeSubgoals` 认知负荷过高，拆分 | 可维护性 |
| 6 | `ToolSchemaValidator.validateNode` 拆分 | 可维护性 |
| 7 | 任务分类逻辑提取为常量/枚举 | 可读性 |
| 8 | 日志脱敏（用户名、用户输入） | 安全合规 |
| 9 | 默认密码移至环境变量 | 安全合规 |
| 10 | SemanticMemoryService 加 Redis 缓存 | 性能 |

### 🟢 下个迭代

| # | 事项 | 影响 |
|---|------|------|
| 11 | embedBatch 并行化 | 性能 |
| 12 | pgvector COPY 协议批量插入 | 性能 |
| 13 | AiChatController 添加 @PreAuthorize | 安全 |
| 14 | 单元测试覆盖 | 质量 |
| 15 | ADR 架构决策记录 | 文档 |

---

## 9. 与上次审查对比

| 维度 | 上次 | 本次 | 变化 |
|------|------|------|------|
| 总体评分 | 6.5/10 | 6.8/10 | +0.3 |
| 严重问题 | 3 | 5 | +2（新增 SSRF + Token 轮换） |
| 中等问题 | 8 | 12 | +4（新增日志脱敏等） |
| 已修复 | 11 | 11 | 连接泄漏、SQL 注入、内存泄漏等 |
| 新发现 | - | 6 | 通过知识图谱发现的新问题 |

**说明**：评分提升是因为修复了连接泄漏等严重问题。
严重问题数量增加是因为本次分析范围扩大到全项目（包括认证、工具执行等）。

---

## 10. 总结

### 强项
1. **架构设计合理**：三层分离、适配层解耦、接口最小化
2. **安全基线良好**：JWT + 国密 + XSS 过滤 + 登录锁定
3. **Wenshi 创新性强**：经验复用机制、四级递减路由

### 弱项
1. **空实现较多**：BGE-small tokenizer、Critic 验证、评估器
2. **SSRF 风险**：HTTP 工具执行无 URL 白名单
3. **多租户安全**：依赖应用层过滤，DB 层无 RLS
4. **测试覆盖为零**：Wenshi 模块无单元测试

### 建议
1. 优先修复 SSRF 和 Token 轮换（安全底线）
2. 部署 pgvector + BGE-small 后执行端到端验证
3. 补充单元测试（目标覆盖率 > 60%）
