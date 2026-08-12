# 格物平台（gewu-platform）—— 全景架构分析文档

> 版本：v1.0  ·  日期：2026-07-17  ·  状态：已完成
>
> 本文档对格物平台的完整架构进行系统性分析，涵盖架构设计、技术选型、模块划分、
> 功能实现、数据模型、接口契约和整体逻辑结构。

---

## 1. 系统上下文（C4 Level 1）

### 1.1 系统定位

> **格物平台**是一个 AI 驱动的智能开发协作平台，提供 Agent 智能体会话、工作流编排、
> 代码沙箱执行、项目管理等核心能力，并通过 Wenshi 架构为 LLM 提供知识增强。

### 1.2 系统边界

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         格物平台                                          │
│                                                                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  │
│  │ 用户认证  │  │ Agent    │  │ 工作流   │  │ 沙箱执行  │  │ Wenshi   │  │
│  │ 权限管理  │  │ 智能体   │  │ 编排引擎  │  │ 代码运行  │  │ 知识增强  │  │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────┘  │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
       ↑              ↑              ↑              ↑              ↑
       │              │              │              │              │
   Web 前端      LLM 服务      外部 API       Docker       PostgreSQL
  (gewu-web)  (DeepSeek/Qwen)  (MCP Server)   Socket      + pgvector
```

### 1.3 外部依赖

| 依赖 | 用途 | 协议 |
|------|------|------|
| DeepSeek / Qwen / OpenAI | LLM 推理 | HTTP SSE |
| MCP Server | 外部工具接入 | stdio / SSE |
| Docker | 沙箱容器 | Socket |
| PostgreSQL + pgvector | 向量存储 | JDBC |
| Redis/DragonflyDB | 缓存 | Redis Protocol |
| RocketMQ | 消息队列 | TCP |

---

## 2. 架构风格

### 2.1 风格判定

根据决策树：
- 团队规模：中型 → 适用微服务
- 业务特征：多租户 SaaS + Agent 编排 → **微服务 + 认知编排层**
- 事件特征：对话流式 + 工具异步 → **响应式编程（Reactor Flux）**

### 2.2 实际架构：模块化单体 + 微服务混合

```
当前阶段（开发期）：
  Maven 多模块单体部署
  ├── gewu-interface    ← API 网关 + Controller
  ├── gewu-application  ← 业务逻辑
  ├── gewu-domain       ← 领域实体
  ├── gewu-infrastructure ← 基础设施
  ├── gewu-gateway      ← 独立网关服务
  ├── gewu-sandbox      ← 独立沙箱服务
  └── gewu-common       ← 公共工具

未来演进（生产期）：
  微服务拆分
  ├── gewu-auth-service     ← 认证服务
  ├── gewu-agent-service    ← Agent 服务
  ├── gewu-workflow-service ← 工作流服务
  ├── gewu-sandbox-service  ← 沙箱服务
  └── gewu-wenshi-service   ← 知识增强服务
```

---

## 3. 模块划分

### 3.1 Maven 模块结构

| 模块 | 职责 | 代码量（文件） |
|------|------|--------------|
| `gewu-common` | 公共工具、枚举、异常、JWT、加密 | 18 |
| `gewu-domain` | 领域实体、值对象 | 28 |
| `gewu-infrastructure` | LLM 客户端、Mapper、缓存、MCP、Wenshi 适配层 | 55 |
| `gewu-application` | 业务服务、DTO、编排引擎 | 42 |
| `gewu-interface` | Controller、安全配置、异常处理 | 25 |
| `gewu-gateway` | 网关、路由、限流、熔断 | 8 |
| `gewu-sandbox` | 代码沙箱执行 | 25 |
| **合计** | | **305** |

### 3.2 分层架构

```
┌─────────────────────────────────────────────────────────┐
│  接口层（gewu-interface）                                 │
│  Controller → 安全过滤 → 请求校验 → 响应封装             │
├─────────────────────────────────────────────────────────┤
│  应用层（gewu-application）                               │
│  Service → DTO 编排 → 事务管理 → 事件发布                │
├─────────────────────────────────────────────────────────┤
│  领域层（gewu-domain）                                    │
│  Entity → ValueObject → 领域接口                         │
├─────────────────────────────────────────────────────────┤
│  基础设施层（gewu-infrastructure）                         │
│  Mapper → LLM Client → Cache → MCP → Wenshi Adapter     │
└─────────────────────────────────────────────────────────┘
```

---

## 4. 核心业务域

### 4.1 业务域地图

```
格物平台
├── 用户域（User）
│   ├── 用户账户（UserAccount）
│   ├── 角色权限（Role / Permission / RolePermission）
│   └── 用户角色关联（UserRole）
│
├── 认证域（Auth）
│   ├── JWT 认证（JwtUtil / JwtAuthenticationFilter）
│   ├── 登录安全（LoginSecurityService）
│   └── 国密算法（SM2 / SM3 / SM4）
│
├── 项目域（Project）
│   ├── 项目（Project）
│   ├── 项目成员（ProjectMember）
│   └── 项目目录（ProjectDirectory）
│
├── 会话域（Session）
│   ├── 会话（Session）
│   ├── 会话消息（SessionMessage）
│   ├── 会话成员（SessionMember）
│   └── 上下文压缩（ContextCompressor）
│
├── Agent 域（Agent）
│   ├── Agent 定义（Agent）
│   ├── Agent 工具（AgentTool）
│   ├── Agent 执行（AgentExecution）
│   ├── MCP Server（McpServer）
│   └── 权限评估（PermissionEvaluationService）
│
├── 工作流域（Workflow）
│   ├── 流程定义（Workflow / WorkflowNode / WorkflowTransition）
│   ├── 流程实例（WorkflowInstance / WorkflowNodeInstance）
│   ├── 流程权限（WorkflowPermission / WorkflowPermissionMatrix）
│   └── 流程通知（WorkflowNotification）
│
├── 沙箱域（Sandbox）
│   ├── 沙箱实例（Sandbox）
│   ├── 沙箱配置（SandboxConfig）
│   └── 沙箱审计（SandboxAuditLog）
│
└── Wenshi 域（知识增强）
    ├── 知识层（Semantic / Episodic / Procedural / Parametric）
    ├── 推理层（Planner / SolverRouter / Critic）
    ├── 学习层（ExperienceExtractor / QualityAssessor / SkillEvolver / Reflection）
    └── 适配层（EmbeddingAdapter / VectorStoreAdapter / TenantIsolation）
```

---

## 5. 核心流程

### 5.1 Agent 对话流程（核心链路）

```
用户输入
  │
  ▼
AiChatController.chat/chatStream
  │
  ├── wenshi.routing == "wenshi" ?
  │     ├── YES → WenshiReasoningEngine.reason()
  │     │         ├── Planner.plan() → 任务分解
  │     │         ├── MemoryRouter.route() → 记忆检索
  │     │         ├── SolverRouter.selectStrategy() → 策略选择
  │     │         │   ├── EXPERIENCE_REUSE → 经验复用（零 LLM）
  │     │         │   ├── KNOWLEDGE_LOOKUP → 知识检索（零 LLM）
  │     │         │   ├── TOOL_EXECUTION → 工具执行（低 LLM）
  │     │         │   └── LLM_REASONING → LLM 推理（高 LLM）
  │     │         ├── Critic.evaluate() → 结果验证
  │     │         └── ExperienceExtractor.extract() → 经验沉淀
  │     │
  │     └── NO  → AgentExecutionEngine.executeAgent()
  │               ├── buildMessages() → 构建上下文
  │               ├── LlmClient.chat() → LLM 调用
  │               ├── ToolExecutionService.executeTool() → 工具执行
  │               └── 递归直到完成或超限
  │
  ▼
SessionContextService.appendChatInteraction() → 持久化
  │
  ▼
返回响应（同步 / SSE 流式）
```

### 5.2 工具执行流程

```
ToolExecutionService.executeTool()
  │
  ├── 1. 查询工具定义（AgentTool）
  ├── 2. 参数校验（ToolSchemaValidator）
  ├── 3. 权限评估（PermissionEvaluationService）
  │       ├── deny → 拒绝
  │       ├── requireApproval → 需确认
  │       └── allow → 继续
  ├── 4. 按 toolType 分支
  │       ├── HTTP → HttpClient 调用
  │       ├── code_execute → Sandbox 执行
  │       └── mcp → McpServerManager 调用
  ├── 5. 审计日志（AuditLogService）
  └── 6. 返回 ToolResult
```

### 5.3 Wenshi 知识写入流程

```
数据来源
  ├── 手动录入（API）
  ├── 文档导入（KnowledgeIngestionService）
  │       ├── 文本分块（按段落，200-500 字，重叠 50 字）
  │       ├── BGE-small embedding（384 维向量）
  │       └── PgvectorAdapter.upsert() → 批量写入
  └── 对话沉淀（ExperienceExtractor）
```

### 5.4 Wenshi 知识检索流程

```
用户输入
  │
  ▼
MemoryRouter.route(taskType)
  │
  ├── DATA_QUERY → 语义记忆（top 5）+ 程序性记忆（TOOL）
  ├── DATA_ANALYSIS → 语义记忆 + 情景记忆 + 程序性记忆（SOP）
  ├── PROCESS_EXECUTION → 程序性记忆（SOP）+ 参数化记忆
  └── KNOWLEDGE_QA → 语义记忆（top 5）
  │
  ▼
PgvectorAdapter.search(query, topK, filters)
  │
  ├── embedding → BGE-small 编码
  ├── WHERE tenant_id = ? → 多租户过滤
  ├── ORDER BY embedding <=> ? → cosine 距离排序
  └── LIMIT topK → 返回 Top-K
  │
  ▼
MemoryInjector.prepareInjection() → 按需注入 LLM prompt
```

---

## 6. 数据模型

### 6.1 核心实体关系

```
UserAccount ─┬─ UserRole ── Role ── RolePermission ── Permission
             │
             ├─ ProjectMember ── Project ── ProjectDirectory
             │
             ├─ SessionMember ── Session ── SessionMessage
             │                      │
             │                      └── agent → Agent
             │
             └─ AgentExecution ── Agent ── AgentTool ── McpServer
                                              │
                                              └── PermissionEvaluation

Workflow ─┬─ WorkflowNode
           ├── WorkflowTransition
           ├── WorkflowPermission
           ├── WorkflowPermissionMatrix
           ├── WorkflowInstance ── WorkflowNodeInstance
           └── WorkflowNotification

Sandbox ── SandboxConfig
         └── SandboxAuditLog
```

### 6.2 Wenshi 数据模型

```
wenshi_semantic_fragment（语义记忆）
  ├── id, tenant_id, owner_user_id
  ├── content（知识内容）
  ├── embedding（384 维向量，pgvector 索引）
  ├── source（来源）, confidence（置信度）
  └── 索引：tenant_id + HNSW(embedding)

wenshi_episodic_event（情景记忆）
  ├── id, tenant_id, user_id, session_id
  ├── event_type（事件类型）
  ├── content（事件内容）
  ├── embedding（384 维向量）
  └── 索引：tenant_id+created_at, HNSW(embedding)

wenshi_procedural_memory（程序性记忆）
  ├── id, tenant_id, type（TOOL/SOP/SKILL）
  ├── name, description, definition（JSON）
  ├── embedding（语义检索）
  ├── usage_count, success_rate, skill_level
  └── 索引：tenant_id+type, HNSW(embedding)

wenshi_experience（经验库）
  ├── id, tenant_id, scenario_hash
  ├── scenario（场景）, strategy（策略）
  ├── outcome（结果）, score（得分）
  ├── embedding（场景语义向量）
  ├── lesson（教训）, hit_count（复用次数）
  └── 索引：scenario_hash, HNSW(embedding)

wenshi_user_profile（参数化记忆）
  ├── id, tenant_id, user_id
  ├── profile_key, profile_value（JSON）
  └── 唯一约束：tenant_id + user_id + profile_key

wenshi_reasoning_trace（推理轨迹）
  ├── id, tenant_id, session_id, task_id
  ├── plan_tree（JSON）, trace_steps（JSON[]）
  ├── used_knowledge, reused_experience_id
  ├── token_stats, reasoning_ms
  └── from_experience
```

### 6.3 数据库选型

| 存储类型 | 技术 | 用途 |
|---------|------|------|
| 关系数据 | OceanBase / MySQL 8.0 | 核心业务数据 |
| 向量数据 | PostgreSQL + pgvector | Wenshi 知识库 |
| 缓存 | Redis / DragonflyDB | 会话缓存、分布式缓存 |
| 消息队列 | RocketMQ | 异步事件、任务调度 |

---

## 7. 接口契约

### 7.1 REST API 概览

| 控制器 | 路径前缀 | 核心接口 |
|--------|---------|---------|
| AiChatController | `/api/v1/ai` | POST /chat, POST /chat/stream, GET /models |
| AgentController | `/api/v1/agents` | CRUD Agent |
| AgentExecutionController | `/api/v1/agent-executions` | 执行 Agent |
| AgentToolController | `/api/v1/agent-tools` | 工具 CRUD |
| AuthController | `/api/v1/auth` | 登录、注册、刷新 Token |
| ChatSessionController | `/api/v1/chat-sessions` | 会话管理 |
| McpServerController | `/api/v1/mcp-servers` | MCP Server 管理 |
| MessageController | `/api/v1/messages` | 消息 CRUD |
| ProjectController | `/api/v1/projects` | 项目管理 |
| SessionController | `/api/v1/sessions` | 会话管理 |
| UserController | `/api/v1/users` | 用户管理 |
| WorkflowController | `/api/v1/workflows` | 工作流定义 CRUD |
| WorkflowInstanceController | `/api/v1/workflow-instances` | 工作流实例管理 |

### 7.2 Wenshi 核心接口

| 接口 | 类 | 说明 |
|------|---|------|
| `WenshiReasoningEngine.reason()` | reasoning | 同步推理入口 |
| `WenshiReasoningEngine.reasonStream()` | reasoning | 流式推理入口 |
| `MemoryRouter.route()` | knowledge | 记忆路由 |
| `Planner.plan()` | reasoning | 任务分解 |
| `SolverRouter.selectStrategy()` | reasoning | 策略选择 |
| `Critic.evaluate()` | reasoning | 结果验证 |
| `EmbeddingAdapter.embed()` | adapter | 文本编码 |
| `PgvectorAdapter.search()` | adapter | 向量检索 |
| `ExperienceExtractor.extract()` | learning | 经验抽取 |
| `QualityAssessor.assess()` | learning | 质量评估 |
| `SkillEvolver.evolve()` | learning | 技能演化 |
| `ReflectionAgent.reflect()` | learning | 反思复盘 |

---

## 8. 技术选型决策

### 8.1 已定型决策

| 决策项 | 选择 | 理由 |
|--------|------|------|
| 后端框架 | Spring Boot 3.2.5 | 生态成熟，响应式支持 |
| ORM | MyBatis-Plus 3.5.5 | 与现有项目一致 |
| 向量库 | pgvector（PG 扩展） | 复用 PG 栈，运维简单 |
| 嵌入模型 | BGE-small（384 维） | 中文优化，CPU 可运行 |
| 推理引擎 | ONNX Runtime | 跨平台，CPU/GPU 通用 |
| LLM 适配 | 适配器模式 | 模型无关，可切换 |
| 租户隔离 | 行级隔离（默认） | 成本可控，按需升级 |
| API 风格 | REST + SSE 流式 | 兼容前端和 Agent |
| 安全 | JWT + 国密算法 | 合规要求 |
| 缓存 | Redis/DragonflyDB | 高性能 KV |

### 8.2 Wenshi 架构决策

| 决策项 | 选择 |
|--------|------|
| Planner 任务分解 | 混合策略（先模板后 LLM） |
| Solver 策略路由 | 经验优先四级递减 |
| Critic 接地验证 | 外部验证优先三级递进 |
| 语义记忆存储 | 图谱+向量混合 |
| 情景记忆存储 | 时序+向量 |
| 程序性记忆存储 | 分类存储统一接口 |
| 参数化记忆存储 | KV+DB 双写 |
| 记忆路由策略 | 规则优先+LLM 兜底 |
| 记忆注入方式 | 按需注入 |
| 经验抽取方式 | 混合（规则为主+LLM 补充） |
| 经验质量评估 | 混合（规则初筛+LLM 精评） |
| 技能封装演化 | 混合（规则提取+LLM 补充） |
| 反思复盘执行 | 混合（规则匹配+LLM 深度分析） |
| 学习效果度量 | 混合（在线监控+定期离线评估） |

---

## 9. 安全架构

### 9.1 认证授权

```
请求 → JwtAuthenticationFilter → SecurityContext
     → @PreAuthorize / 自定义注解
     → PermissionEvaluationService
     → allow / deny / requireApproval
```

### 9.2 安全措施

| 措施 | 实现 |
|------|------|
| 认证 | JWT（HS256）+ Refresh Token |
| 密码 | SM3 哈希 + 盐值 |
| 传输 | HTTPS（SM2 国密 TLS） |
| XSS | XssFilter + XssRequestWrapper |
| CSRF | SecurityHeadersFilter |
| 限流 | RateLimitFilter + DistributedLockService |
| 审计 | AuditLogAspect + AuditLogService |
| 多租户 | tenant_id 行级隔离 |

---

## 10. 部署架构

### 10.1 开发环境

```
docker-compose up -d
  ├── mysql:3306       ← 核心业务库
  ├── pgvector:5432    ← Wenshi 向量库
  ├── dragonfly:6379   ← 缓存
  └── rocketmq:9876    ← 消息队列

应用启动
  ├── gewu-interface:8081   ← API 入口
  ├── gewu-gateway:8080     ← 网关
  └── gewu-sandbox:8082     ← 沙箱服务
```

### 10.2 生产环境（规划）

```
负载均衡
  ├── gewu-gateway-cluster  ← 网关集群
  ├── gewu-interface-cluster ← API 集群
  ├── gewu-agent-cluster    ← Agent 服务集群
  └── gewu-wenshi-cluster   ← Wenshi 服务集群

数据层
  ├── OceanBase-cluster     ← 核心业务库
  ├── PostgreSQL+pgvector    ← 向量库（独立实例）
  ├── Redis-cluster         ← 缓存集群
  └── RocketMQ-cluster      ← 消息集群
```

---

## 11. 代码统计

### 11.1 按模块

| 模块 | Java 文件 | 代码行数（估） |
|------|----------|--------------|
| gewu-common | 18 | ~1500 |
| gewu-domain | 28 | ~1200 |
| gewu-infrastructure | 55 | ~4500 |
| gewu-application | 42 | ~5000 |
| gewu-interface | 25 | ~2500 |
| gewu-gateway | 8 | ~600 |
| gewu-sandbox | 25 | ~2000 |
| **合计** | **305** | **~17300** |

### 11.2 Wenshi 新增

| 阶段 | 文件 | 代码行数 |
|------|------|---------|
| A（知识层） | 27 | ~910 |
| B（推理层+学习层） | 23 | ~800 |
| C（评估+报告） | 7 | ~600 |
| **合计** | **57** | **~2310** |

---

## 12. 项目当前状态

### 12.1 已完成

| 能力 | 状态 |
|------|------|
| 用户认证（JWT + 国密） | ✅ 完整 |
| 项目管理 | ✅ 完整 |
| 会话管理 | ✅ 完整 |
| Agent 定义与执行 | ✅ 完整 |
| 工具系统（HTTP/MCP/Code） | ✅ 完整 |
| 工作流编排 | ✅ 完整 |
| 代码沙箱 | ✅ 完整 |
| Wenshi 知识层 | ✅ 骨架完成 |
| Wenshi 推理层 | ✅ 骨架完成 |
| Wenshi 学习层 | ✅ 骨架完成 |
| Wenshi 评估框架 | ✅ 骨架完成 |
| 代码审查 + 安全审计 | ✅ 完成 |
| 严重问题修复 | ✅ 完成 |

### 12.2 待完善

| 能力 | 依赖 |
|------|------|
| BGE-small 真实推理 | ONNX 模型文件部署 |
| pgvector 真实检索 | Docker 容器部署 |
| 端到端对比实验 | 真实业务数据 |
| 知识图谱集成 | Neo4j 部署 |
| 论文/专利 | 实验数据 |

---

## 13. 文档清单

| 文档 | 路径 | 内容 |
|------|------|------|
| 架构方案 | `docs/WENSHI-ARCHITECTURE-v3.md` | Wenshi 完整架构 |
| 系统设计 | `docs/WENSHI-SYSTEM-DESIGN.md` | 包结构 + 接口 + DDL |
| 开发计划 | `docs/WENSHI-DEVELOPMENT-PLAN.md` | 4 阶段计划 |
| 阶段 A 验收 | `docs/WENSHI-PHASE-A-REVIEW.md` | 阶段 A 验收 |
| PoC 报告 | `docs/WENSHI-POC-REPORT.md` | PoC 验证报告 |
| 代码审查 | `docs/WENSHI-CODE-REVIEW.md` | 审查+安全审计 |

---

> **总结**：格物平台是一个 AI 驱动的智能开发协作平台，采用模块化单体架构，
> 核心能力包括 Agent 智能体、工作流编排、代码沙箱。Wenshi 作为 LLM 增强层，
> 通过知识层+推理层+学习层三层架构，为 LLM 提供长期记忆、业务知识和经验积累能力。
> 当前骨架代码已完成，待部署基础设施（pgvector + BGE-small）后进入验证阶段。
