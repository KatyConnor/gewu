# 格物平台（gewu-platform）全面架构分析与项目报告

> **报告版本**：V1.0 | **日期**：2026-08-20
> **分析角色**：资深技术架构师（角色03）
> **分析方法**：codebase-memory 知识图谱索引（19,744 节点 / 45,900 边）+ 4 路并行深度代码探索 + 关键链路人工抽查验证
> **代码基线**：commit `5f0f841`（认知型Agent架构实施·阶段一至五核心完成）+ 未提交工作区变更（105 文件，+11,474/-17,926）
> **数据来源**：全部结论来自仓库内源码/配置/SQL/文档实读；关键论断经交叉验证（多路独立探索结果一致 + 主链路源码抽查）
> **置信度**：整体架构与功能清单——**高**；技术债细节——**高**（含源码行号级证据）；完成度主观评级——**中**（基于代码存在性推断，未经运行时验证）

---

## 目录

1. [项目概况](#一项目概况)
2. [总体架构分析](#二总体架构分析)
3. [功能清单与实现完成度](#三功能清单与实现完成度)
4. [核心逻辑实现流程](#四核心逻辑实现流程)
5. [Agent 引擎深度剖析](#五agent-引擎深度剖析)
6. [会话功能深度剖析](#六会话功能深度剖析)
7. [架构评审与风险评估](#七架构评审与风险评估atam)
8. [技术债清单](#八技术债清单按优先级排序)
9. [优化建议](#九优化建议)
10. [扩展路线图](#十扩展路线图)
11. [结论](#十一结论)

---

## 一、项目概况

### 1.1 定位

**格物平台**是「AI 驱动的智能开发协作平台」：以认知型 Agent 引擎为核心，覆盖 SDLC 全生命周期（需求 → 架构 → 开发 → 测试 → 运维）的智能体协作平台，目标等保 2.0 三级合规、信创兼容。

### 1.2 代码规模

| 板块 | 规模 | 说明 |
|------|------|------|
| 后端 Java（8 个 Maven 模块） | 716 文件 / 约 52,430 行 | gewu-application 最重（242 文件/24,740 行），agent-engine 次之（144 文件/10,101 行） |
| 前端 gewu-web（Next.js 14） | 81 个 TS/TSX / 约 16,666 行 | 单入口状态路由，38 个页面组件约 1.48 万行 |
| 测试 | 28 个测试文件 | 集中在会话/安全/需求/wenshi；**agent-engine 零测试** |
| 文档 | docs/ 下 100+ 篇 | design 41 篇、agent-engine 14 篇、wenshi 8 篇、archive 16 篇 |
| AI 协作资产 | agent-sdlc-skills（11 角色/23 技能）+ agent-coding-rules（11 语言规范） | 与引擎 RoleRegistry 内置 12 个 SDLC 角色呼应 |

### 1.3 技术栈

- **后端**：Java 21、Spring Boot 3.2.5、MyBatis-Plus 3.5.5、Reactor（引擎流式核心）、MapStruct、Hutool、Redisson、RocketMQ 5.x（已配置未使用）
- **存储**：MySQL 8.0（主库 gewu_dev，Flyway V1~V32 共 32 个迁移）+ PostgreSQL 16/pgvector（独立 wenshi 库，向量检索）+ DragonflyDB 1.27（Redis 协议）+ MinIO（对象存储）
- **AI 基础设施**：本地 ONNX 推理（bge-small-zh-v1.5，384 维嵌入）、SearXNG 聚合搜索、OpenAI 兼容协议多厂商 LLM（DeepSeek/智谱/豆包/千问等，DB 动态加载）
- **安全**：国密 SM2/SM3/SM4（BouncyCastle 1.77）、JWT（HS256，30min access/7d refresh）、RBAC + 数据权限
- **前端**：Next.js 14.2.35（App Router 骨架但实际单入口状态路由）、React 18、Redux Toolkit 2.12、Tailwind 3.4、自研工作流画布（弃用 React Flow，见 SP-04 专项）
- **可观测**：Prometheus + Grafana + Loki + Promtail + Jaeger（OTLP），业务指标 7 个 agent-* 自定义面板
- **部署**：K8s（9 个清单）+ docker-compose（开发/生产两套）+ 三套 CI（GitHub/GitLab/Jenkins）并存

### 1.4 仓库状态警示

当前工作区存在 **105 个文件、+11,474/-17,926 行的未提交变更**：agent-engine 认知架构文件已 staged（A/MM/AM 状态）、gewu-web 存在 Vite → Next.js 迁移痕迹（vite.config.ts/tsconfig.node.json 被删除）、monitoring compose 有修改。**建议尽快整理提交**，避免大范围变更长期悬空（风险见 §7）。

---

## 二、总体架构分析

### 2.1 架构风格与进程拓扑

**模块化单体（Modular Monolith）+ DDD 分层 + 独立引擎库 + 边车沙箱**，符合团队规模的合理选择（架构决策树：团队 <10-20 人、业务内聚 → 单体优先）。

```
                          ┌─────────────────────────────────┐
   浏览器/Web前端 ──────► │  gewu-gateway :8080             │
   (Next.js :5001)       │  JWT鉴权/限流(100次/60s)/熔断    │
                          └───────┬──────────────┬──────────┘
                                  │ /api/**      │ /api/v1/sandboxes/**
                          ┌───────▼──────┐  ┌────▼─────────────┐
                          │ gewu-        │  │ gewu-sandbox     │
                          │ interface    │  │ :8082 (独立进程)  │
                          │ :8081 主服务  │  │ Docker代码执行隔离 │
                          │ (全部业务     │  │ 命令校验/审计/调度 │
                          │  Controller) │  └──────────────────┘
                          └──────┬───────┘
        ┌──────────┬────────────┼──────────────┬─────────────┐
   ┌────▼────┐ ┌───▼────┐ ┌─────▼──────┐ ┌────▼─────────┐ ┌──▼──────────┐
   │gewu-    │ │gewu-   │ │gewu-       │ │gewu-        │ │gewu-agent-  │
   │application│ │domain │ │infrastructure│ │common      │ │engine(库)   │
   │应用服务/  │ │实体/   │ │MyBatis适配器/│ │结果/上下文/  │ │认知Agent引擎 │
   │SPI适配器  │ │贫血模型│ │LLM/向量/存储 │ │国密/工具     │ │(无启动类,SPI)│
   └──────────┘ └────────┘ └────────────┘ └─────────────┘ └─────────────┘
        │                    │
   ┌────▼────────────────────▼──────────────────────────────────────┐
   │ MySQL(主库,Flyway V1-V32) │ PG/pgvector(wenshi) │ DragonflyDB  │
   │ MinIO │ SearXNG │ RocketMQ(未使用) │ ONNX(bge-small本地嵌入)   │
   └────────────────────────────────────────────────────────────────┘
```

### 2.2 模块职责与依赖方向

依赖方向严格单向：`interface → application → domain ← infrastructure`，`common` 被所有层引用，`agent-engine` 仅被 `application` 依赖（通过 Spring Boot 3 标准自动装配 `AutoConfiguration.imports` 注入）。

| 模块 | 行数 | 职责 | 架构评价 |
|------|------|------|----------|
| gewu-interface | 4,895 | 33 个 REST Controller + 安全过滤器链 + 主启动类 | 薄接口层，符合 DDD |
| gewu-application | 24,740 | 业务编排 + **26 个 SPI 适配器**（Db*/Wenshi*/Sm*/Otel* 系列）桥接引擎 | 最重模块；适配器层设计是亮点 |
| gewu-domain | 1,731 | 22 个包的 MyBatis-Plus 实体 | **贫血模型**（纯 @Data 实体，无业务方法/聚合边界） |
| gewu-infrastructure | 5,497 | Mapper、LLM 客户端工厂、pgvector、MinIO、国密、事件 | 适配器实现完整 |
| gewu-agent-engine | 10,101 | 可独立复用的认知 Agent 引擎框架 | **刻意零 DB/Web 依赖**，SPI 解耦彻底 |
| gewu-sandbox | 1,963 | Docker 沙箱独立进程 | internal-api-key 鉴权，独立审计 |
| gewu-gateway | 264 | JWT 过滤 + Redisson 限流 + Resilience4j 熔断 | 职责单一 |
| gewu-common | 3,239 | Result 信封、UserContext、SM2/3/4 工具、审计注解 | 复用度高（Result.success fan-in=263） |

### 2.3 关键架构决策（事实性 ADR 摘录）

| 决策 | 依据 | 评价 |
|------|------|------|
| 引擎以 SPI + NoOp 默认实现外置全部持久化 | `NoOpPersistenceService` 保证零业务实现可启动 | ✅ 优秀，框架可独立测试/开源 |
| SSE 用 StreamingResponseBody 手工 flush 而非 Flux 返回值 | 源码注释：Spring MVC ReactiveTypeHandler 会缓冲整个 Flux | ✅ 务实且注释了动机（AiChatController.java:98-104） |
| 自研工作流画布弃用 React Flow | docs/archive/SP-04 性能专项 | ✅ 有决策记录支撑 |
| 双引擎并存（legacy AgentExecutionEngine / wenshi WenshiReasoningEngine）由配置路由 | `gewu.wenshi.routing.chat/stream` 开关 | ⚠️ 绞杀者模式过渡态，需设定收敛期限 |
| 双存储（MySQL 业务 + PG 向量） | 向量场景独立演进 | ✅ 合理，代价是双数据源运维 |
| 工作流双轨（workflow 顺序推进 / orchestration DAG 编排） | 历史演进产物 | ⚠️ docs/design/41 已识别为 5 大架构问题之一 |

### 2.4 知识图谱结构洞察

Leiden 社区检测显示 **gewu-application 与 gewu-web、gewu-sandbox、gewu-interface、gewu-agent-engine 形成高耦合大簇**（508 成员，内聚 0.65），验证了「应用层为引力中心」的单体特征；gewu-web 自身两个簇内聚度最高（0.83/0.89），前端模块化良好。热点函数 Top10 全部为通用工具（Result.success、BusinessException.of、UserContext.currentUserId），无业务热点失衡。

---

## 三、功能清单与实现完成度

完成度评级标准：🟢 完整（Controller+Service+持久化+前端页面闭环）；🟡 半成品（有实现但链路缺环）；🔵 空壳/规划。

### 3.1 后端 33 个 Controller 分域清单

| # | 业务域 | 主要端点 | 完成度 |
|---|--------|---------|--------|
| 1 | **认证/用户/RBAC/机构/菜单** | `/auth`（login/register/refresh/logout）、`/users`、`/roles`+权限、`/permissions`、`/orgs`、`/menus`（含角色菜单分配、数据权限四档） | 🟢 |
| 2 | **AI 对话** | `/ai/chat`（同步）、`/ai/chat/stream`（SSE 流式，推理模型 10min 超时）、`/ai/models` | 🟢 |
| 3 | **AI 会话** | `/ai/sessions` CRUD + 消息历史 | 🟢（增值功能缺口见 §6.5） |
| 4 | **模型配置** | `/models` providers/模型 CRUD + toggle（DB 动态加载多厂商） | 🟢 |
| 5 | **Agent 管理** | `/agents` CRUD、工具/技能挂载、`/agents/executions`（记录）、`/agents/market`（广场/发布/安装/审核）、`/agents/tools` | 🟢（tools 缺 DELETE 端点 🟡） |
| 6 | **MCP** | `/mcp-servers` CRUD + 工具发现 + 激活 | 🟢（引擎侧 SseMcpClient 有缺陷，见 §8） |
| 7 | **技能** | `/skills` CRUD、技能库安装、发布/审核 | 🟢 |
| 8 | **项目** | `/projects` CRUD、成员、**17 阶段生命周期**（前 7 阶段可回退）、repo clone/pull、`/documents` 阶段文档+版本+Agent 审核流转 | 🟢（业务最重模块） |
| 9 | **需求** | `/requirements` CRUD、状态流转、评审、任务、评论、统计、文件空间、feature 分支创建 | 🟢 |
| 10 | **协作会话** | `/sessions` + `/sessions/{id}/messages`（发送/搜索/编辑/软删）+ `/sse/sessions/{id}` 实时推送 | 🟢 |
| 11 | **用户工作台** | `/workspaces` 文件树、上传下载（MinIO 预签名 7 天）、空间沙箱 | 🟢 |
| 12 | **开发工作台** | `/dev-workspaces` 开发沙箱启停、文件读写（docker exec/cp）、git 全流程、**Git 凭证 SM4 加密** | 🟢 |
| 13 | **工作流** | `/workflows` 定义/发布/图保存 + `/workflows/instances` 启动/完成/挂起/恢复/终止/通知 | 🟢（**顺序推进，非并行 DAG**） |
| 14 | **智能体编排** | `/orchestration` 图 CRUD、同步+SSE 执行、执行实例管理、**自主目标 goals（SSE 推送分解）** | 🟢（引擎侧 pause/resume/cancel 为存根 🟡） |
| 15 | **治理/评估** | `/policies`（版本化策略）、`/evaluations`（LLM-as-Judge、SPC 劣化检测）、`/approvals`（HITL 审批）、`/stats` | 🟢 |
| 16 | **审计** | `/audit-chain`（**SHA-256 哈希链防篡改校验**）、`/admin/audits`（聚合审核中心） | 🟢 |
| 17 | **沙箱服务** | `/sandboxes` 全生命周期 + `/execute` 临时沙箱 + `/sandbox-audits`（独立进程 :8082） | 🟢 |

### 3.2 前端 26 个页面（单入口状态路由）

核心链路（登录 → 会话列表 → SSE 流式聊天 → 过程时间线 → 消息持久化回放）**实现完整且工程质量高**：`ProcessTracker` 把流式事件累积为交错时间线（对标 zcode 效果）、幂等回调（safeComplete/safeError）、StrictMode 双调用防重复。

| 页面族 | 页面 | 状态 |
|--------|------|------|
| 聊天 | ChatPage/ChatHomeView/AIProcessTimeline | 🟢 核心亮点 |
| Agent | AgentManage/AgentMarket/MyAgents | 🟢 |
| 技能 | SkillLibrary/MySkills/SkillAudit | 🟢 |
| 管理后台 | User/Role/Menu/Org/AuditCenter | 🟢 |
| 工程域 | Projects/ProjectDetail/Requirements 全家桶/Prototype/Workflow+Canvas/Orchestration | 🟢（Prototype 页有静态占位 🟡） |
| 工作台 | Workspace/DevWorkspace/Sandbox/McpServer | 🟢 |
| 其他 | Dashboard/Usage/Settings（多主题） | 🟢（Dashboard 部分指标 mock 🟡） |

**前端遗留**：Redux store 内残留 mock 会话数据、ChatPage 用户名「张明远」硬编码、会话搜索框未接事件、`src/slices/` 空目录、API 封装双轨并存（axios request.ts 与自带 authFetch 的 session.ts 等两套）。

### 3.3 各子系统完成度

| 子系统 | 完成度 | 说明 |
|--------|--------|------|
| gewu-web 前端 | ★★★★☆ | 核心闭环完整，少量静态占位 |
| gewu-agent-engine | ★★★★☆ | 架构完整度高但 ReflexionRuntime 简化、编排节点类型半占位、**零测试** |
| Wenshi 认知子系统 | ★★★☆☆ | 三层（记忆/推理/学习）代码齐全、有 4 个单测；但 PoC 自述「整体效果接近纯 LLM，待完整实验」；参数化记忆/图谱存储仍是设计稿 |
| 沙箱 | ★★★★☆ | 生命周期/审计/调度完整 |
| 网关 | ★★★★☆ | 鉴权/限流/熔断齐备 |
| 部署与监控 | ★★★★☆ | K8s/监控/告警/看板齐全；prod compose 有 YAML 缩进瑕疵、前端与 gateway/sandbox 未纳入容器化 |
| CI/CD | ★★★☆☆ | 构建/测试/镜像真实可用；GitHub 路线部署阶段为 echo 占位；三套 CI 并存需收敛 |
| 性能测试 | ★★☆☆☆ | 仅测试计划文档（750 并发目标），**jmx 脚本缺失** |
| gewu-desktop | ☆☆☆☆☆ | 仅两个空目录，零代码 |
| 认知型 Agent 五阶段计划 | 阶段一~四 ✅ 完成 / 阶段五 🔶 部分 | 与 docs/design/41 V2.0 对应（WORM 审计链、多场景框架已做；在线学习/红蓝对抗/知识图谱/联邦为远期） |

---

## 四、核心逻辑实现流程

### 4.1 请求全链路（横切）

```
前端 authFetch/axios(Bearer)
  → Gateway :8080 JwtAuthFilter（校验 JWT，注入 X-User-Id/X-Username/X-Roles 头）
  → RateLimitFilter（Redisson RRateLimiter，userId/IP 维度 100次/60s）
  → Resilience4j CircuitBreaker（50% 失败率熔断 + fallback）
  → 主服务 :8081 JwtAuthenticationFilter（二级校验）+ XssFilter + SecurityHeadersFilter
  → UserContext（ThreadLocal）→ Controller → Service
  → 统一 Result<T> 信封（code=10000 成功）/ BusinessException.of / 全局异常处理
```

### 4.2 AI 对话流式主链路（方法级，最核心链路）

```
POST /api/v1/ai/chat/stream (AiChatController.chatStream)
├─ 路由分流（gewu.wenshi.routing.stream：默认 legacy，生产配 wenshi）
│
├─ [legacy 路径] chatStreamViaLegacy
│   └─ AgentExecutionEngine.executeAgentStream(AgentExecutionRequest)
│       ├─ resolveAgentId：显式 agentId > session.agent（会话级绑定回退）
│       ├─ loadAgent → agentMapper.selectById
│       ├─ messageBuilder.resolveProviderAndModel(agent, model)
│       ├─ AgentTask.builder()...build()
│       └─ agentExecutor.executeStream(task) → ReactAgentExecutor（见 §5.3）
│           └─ 返回 Flux<AgentEvent> → .map(toChunk) → Flux<AgentChunk>
│
├─ [wenshi 路径] chatStreamViaWenshi
│   └─ WenshiReasoningEngine.reasonStream → Flux<WenshiReasoningChunk>
│       （Planner → SolverRouter 四级路由 → Critic → 记忆注入 → SearXNG 联网检索+结果验证 → 文件产物）
│
├─ doOnNext 累积 content + file 事件
└─ 返回 StreamingResponseBody：
    ├─ eventFlux.subscribe：每事件 "data: {json}\n\n" + writer.flush()（逐事件实时推送）
    ├─ error → data: {"type":"error",...}；complete → "data: [DONE]\n\n"
    ├─ CountDownLatch.await() 阻塞至完成；客户端断连 → dispose()
    └─ 流完成且无错 → sessionContextService.appendChatInteraction(sessionId, userId,
        userMessage, assistantContent)   // assistantContent 末尾 <!--FILES:json--> 内嵌文件卡片
```

前端配套（gewu-web/src/lib/chat.ts）：**fetch POST + ReadableStream 手工解析 SSE**（因需 POST body 与 Authorization 头，不用 EventSource），TextDecoder 增量解码、残包 buffer 保留、`[DONE]` 与 `done` 双兼容；直连后端 8081 绕过 Next 代理缓冲（与 K8s ingress `proxy-buffering: off` 注解呼应）。

### 4.3 Agent 引擎同步执行链（ReAct）

```
AgentEngine.execute(task) → ReactAgentExecutor.execute(task)
1. promptInjectionDetector.checkInput(message)        // 高风险注入 → 拦截抛 PROMPT_INJECTION_DETECTED
2. loadAgent(agentId) → PersistenceService.loadAgent   // SPI，Db 适配器读 MySQL
3. 解析 provider/model（task 显式 > AgentSpec；缺 → MODEL_NOT_RESOLVED）
4. planExecution 前置决策链（同步/流式共用）：
   perceptionEngine.perceive(message) → Intent         // 感知（Wenshi 适配器桥接）
   complexityRouter.route(message, intent) → L1/L2/L3 // 复杂度路由（纯规则，零 LLM）
   budgetController.createBudget(level)                // 四维预算差异化配额
   modelSelector.select(...)（可选，失败静默）
5. buildMessages：SessionContextService.buildContextMessages(sessionId, 50)
   → MessageBuilder（system prompt + 模式/思维指令 + 温度映射）
   → memoryRouter.inject(domain, messages, message)   // 长期记忆注入
6. responseCache.get(message, agentId)                 // 语义缓存命中 → 零成本返回
7. ReAct 循环（round < maxToolRounds=10）：
   ├─ budgetController.shouldStop(budget)              // BLOCK → BUDGET_EXCEEDED
   ├─ traceService.startSpan("llm_call") → client.chat(llmRequest)
   ├─ budgetController.consume(tokens, cost)           // 记账
   ├─ 无 toolCalls → outputSanitizer.checkOutput（PII 脱敏）
   │   → storeExperience（episodic 记忆沉淀）→ recordSuccess → responseCache.put → 返回
   └─ 有 toolCalls → assistant 回灌 → CompletableFuture.supplyAsync 并行执行工具
       （ToolExecutor 五段管线，见 §5.5）→ tool 消息回灌 → 下一轮
8. 轮次耗尽 → TOOL_ROUNDS_EXCEEDED
```

### 4.4 编排引擎链路（多 Agent 协作）

```
OrchestrationController（REST/SSE）
└─ OrchestrationService → OrchestrationEngine
   ├─ executeStream(graph, ctx)：按 graph.mode 分派 ModeHandler
   │   ├─ PIPELINE：拓扑排序串行，产出逐节点传递；HUMAN 节点 → HITL 审批阻塞
   │   ├─ SUPERVISOR：Supervisor 节点以 TASK_DELEGATE/TASK_RESULT 信封调度专家
   │   ├─ SWARM：输出解析 HANDOFF:target|reason / FINISH 指令传控（maxHandoffs=8 防环）
   │   └─ DEBATE：辩手并行 flatMap → 裁判节点裁决
   ├─ runSync 后处理：制品契约校验（ArtifactValidator）→ 冲突解决（ConflictResolver 四策略）
   └─ executeGoal(autonomousGoal)：AutonomousExecutor 自主循环
       【AntiRunawayGuard 六重边界 → goalPlanner.decompose 分解 → orchestrator.runSync
        → 产出入 VersionedContext → 预算记账 → evolutionHook.onGraphComplete
        → verifyGoal（DualLoopVerifier 内环 Critic≤5轮 + 外环仲裁≤2轮 + ConfidenceGate 门控）
        → 通过 onGoalSuccess / 失败 reflection 事件迭代重试（maxIterations=5）】
```

### 4.5 HITL 人机审批链路

```
Pipeline HUMAN 节点 / ConflictResolver 策略4 / ConfidenceGate.ESCALATE_HITL
→ HitlGateway.requestApproval(ApprovalRequest)   // 引擎 SPI
→ DbHitlGatewayAdapter（应用层适配器）：
   sseEventManager.sendEvent(executionId, "approval_required", req)  // SseEmitter 推送前端
   + Sinks.One<HumanDecision> 挂起等待（默认 30min 超时自动拒绝）
→ 前端 AuditCenterPage 展示 → POST /api/v1/approvals 批准/驳回
→ hitlGateway.submitDecision → 恢复挂起的 Mono
→ APPROVED：审批意见并入产出继续；REJECTED：ctx.rollbackOneVersion()（copy-on-write 回滚）+ graph_complete(FAILED) 留痕
```

### 4.6 会话上下文管理链路

```
buildContextMessages(sessionId, 50)：
  按 seq 倒序 LIMIT 50 → reverse
  → 估算 token（content.length()/4）≤ 4000 → 直接映射
  → 超阈值 → 分离 system 消息 → ContextCompressor 压缩旧消息为 [对话历史摘要]
     （llm 模式：qwen-plus 生成 ≤800 token 摘要，hash 缓存 2h；失败回退 truncate ≤4000 字符）
  → 保留最近 6 条 + 摘要 → 结果写 Redis（2h TTL）
```

### 4.7 沙箱执行链路

```
工具执行（sandboxEnabled + code_execute）→ SandboxExecutor SPI
→ DbSandboxExecutorAdapter → SandboxClient（HTTP + X-Internal-Api-Key）
→ gewu-sandbox :8082 /api/v1/sandboxes
→ CommandValidator（严格）/DevCommandValidator（开发台宽松）校验
→ Docker API（unix socket）执行，资源限额（CPU/内存/磁盘/网络开关）
→ SandboxScheduler：agent 临时沙箱 300s 自动销毁；开发沙箱 TTL 30 天
→ 独立 sandbox_audit 审计表
```

---

## 五、Agent 引擎深度剖析

> 模块：gewu-agent-engine（144 文件 / 10,101 行 / 24 包），Spring Boot 3 自动装配的可独立复用框架库。刻意零数据库/零 Web 依赖（HTTP 用 JDK HttpClient + Reactor Flux），pom 仅 5 个依赖。

### 5.1 包结构总览

| 包 | 类数 | 职责 |
|----|------|------|
| `core` (+event) | 6 | 引擎门面 AgentEngine、执行器 SPI、ReactAgentExecutor（662 行核心）、AgentTask、AgentEngineConfig、AgentEvent（16 种事件） |
| `cognition` | 15 | 感知/推理内核/反思/仲裁/复杂度路由/双系统路由/置信门控/进化钩子（5 个 NoOp） |
| `orchestration` (+mode/model/role/runtime) | 33 | 编排门面、调度器、目标规划、自主执行、防失控、生命周期、冲突解决、handoff 解析、版本化上下文、4 模式处理器、双图模型、12 内置角色、3 运行时 |
| `llm` (+model) | 12 | LlmClient SPI、注册中心（三级查找）、OpenAI 兼容客户端（同步+SSE 流式+reasoning 分离+函数调用）、请求构建器 |
| `tool` (+security) | 17 | Tool 接口、@ToolProvider 注解、注册表、执行器（五段管线）、安全链 5 组件 |
| `mcp` | 9 | MCP 客户端（stdio/SSE 两种传输）、服务器管理器、配置源 SPI |
| `memory` | 5 | MemoryFragment（四类记忆）、MemoryStore/MemoryRouter SPI |
| `message` | 4 | 消息构建、SystemPromptComposer、PromptDirective（4 agentMode × 5 thinkingStyle 指令与温度映射） |
| `hitl` | 4 | ApprovalRequest/HumanDecision/HitlGateway SPI |
| `budget` | 3 | 四维预算（Token/时间/成本/轮次）+ L1/L2/L3 分级配额 + 四态（NORMAL/ALERT/DEGRADE/BLOCK） |
| `contract` / `verification` | 3 | 制品契约 Schema 校验、双闭环验证器 |
| `scenario` | 2 | 场景适配器（内置 DEFAULT/CODE_GENERATION/KNOWLEDGE_QA/ARCHITECTURE 四场景） |
| `spi` (+defaults) | 24 | **16 个扩展点接口 + 8 个 NoOp 默认实现** |

### 5.2 SPI 扩展点体系（核心设计）

统一模式：**接口 + @ConditionalOnMissingBean 的 NoOp 默认实现**，业务方注册 Spring Bean 即覆盖。全量 21 个 SPI（关键摘录）：

| SPI | 默认行为 | 平台侧实现（gewu-application/agent/adapter/，共 26 个适配器） |
|-----|---------|---------|
| `AgentExecutor` | ReactAgentExecutor | 未覆盖 |
| `PerceptionEngine` / `ReasoningKernel` / `ReflectionEngine` / `ArbiterEngine` / `EvolutionHook` | NoOp 透传 | Wenshi*Adapter ×5（桥接 Wenshi 认知子系统） |
| `HitlGateway` | 直接批准 | DbHitlGatewayAdapter（SSE + Sinks 挂起恢复） |
| `LlmClient` / `LlmProvider` | 动态 OpenAI 兼容客户端 | LegacyLlmClientAdapter / DbLlmProviderAdapter（DB 动态厂商） |
| `MemoryStore` / `MemoryRouter` | 无记忆 | Wenshi*Adapter（pgvector） |
| `PersistenceService` / `SessionContextService` | 内存 Map / 空历史 | Db*Adapter（MySQL / 上下文压缩服务） |
| `PermissionService` / `AuditService` / `PolicyService` | 全允许 / 日志 / 全放行 | Db*Adapter ×3 |
| `SandboxExecutor` | 抛 SANDBOX_NOT_CONFIGURED | DbSandboxExecutorAdapter |
| `ApiKeyDecryptor` | 原样透传 | SmApiKeyDecryptorAdapter（国密 SM4） |
| `ModelSelector` / `ResponseCache` / `TraceService` / `MetricService` | 不干预 / 不缓存 / 无 | ModelSelectorAdapter / SemanticResponseCacheAdapter（语义缓存）/ OtelTraceServiceAdapter / DbMetricServiceAdapter |

**设计评价**：这是国内 Agent 框架中少见的彻底解耦——引擎不绑定任何存储/权限/审计实现，`NoOpPersistenceService` 使其可作为纯 jar 独立引入。代价是 26 个适配器的胶水代码量与「SPI 泛滥」的认知负担（21 个接口需要使用者理解装配语义）。

### 5.3 ReactAgentExecutor 执行细节

**同步路径**：见 §4.3。关键工程细节：
- 工具并行执行使用专用线程池（8 核心/16 最大/队列 100，CallerRunsPolicy，daemon 线程 `agent-tool-exec`）；
- 每轮 LLM 调用、每次工具调用均包裹 TraceService Span（OTel 桥接）；
- 缓存命中直接返回 LlmResponse，零 LLM 成本。

**流式路径**（streamRound 递归）：
- `client.chatStream`：`Flux.create` + `subscribeOn(boundedElastic)`，**强制 HTTP/1.1**（防 HTTP/2 流控缓冲）；
- chunk 分派：`reasoning` → THINKING 事件；`delta` → CONTENT（StringBuilder 累积）；`toolCallDelta` → ToolCallAccumulator 按 id 增量累积（流式函数调用）；`finishReason` 暂存；
- 每轮流头部 startWith STATUS 事件（"正在思考…"/"正在继续推理…"）；
- 有工具调用：依次发 TOOL_CALL/TOOL_EXECUTING → flatMap 并行执行（`synchronized(messages)` 回灌）→ TOOL_RESULT → concatWith 递归 streamRound(round+1)；
- doFinally：PII 脱敏 + storeExperience + recordSuccess；onErrorResume：失败转 ERROR 事件不向订阅者抛异常。

### 5.4 认知架构集成方式（谁在什么时机调用谁）

| 组件 | 调用时机 | 说明 |
|------|---------|------|
| PerceptionEngine | 每次任务入口（planExecution 第 1 步） | Intent 回填 AgentTask |
| ComplexityRouter（+DualSystemRouter） | 第 2 步 | 输出 L1-L3 → 决定预算配额；**SystemChoice.runtimeMode/modelTier 目前仅日志输出，未真正切换运行时**（阶段四遗留） |
| BudgetController | 全程 | L1=default/5 token+30s+3轮；L3=×3+×4+×2；70% ALERT/90% DEGRADE/100% BLOCK |
| ReasoningKernel | 仅 AutonomousExecutor.verifyGoal（Critic）与 ConflictResolver | **不在单次对话主链路** |
| ReflectionEngine | 设计供 ReflexionRuntime | **当前未实际调用**（ReflexionRuntime 简化） |
| ArbiterEngine | DualLoopVerifier 外环、ConflictResolver 策略 3 | |
| EvolutionHook | AutonomousExecutor 图完成/验收成败 | |
| ConfidenceGate | verifyGoal：critic 0.4 + tool 0.25 + historical 0.2 + contract 0.15 加权 → ADOPT/RETRY_CHANGE_MODEL/RETRY_CHANGE_CONTEXT/ESCALATE_HITL 四级决策 | |

**认知链路总结**：单次对话只走「感知→复杂度→预算→模型路由」前置链；Planner/Critic/反思/仲裁/进化属于自主目标层与编排层。分层清晰，但「深度思考」的实际收益尚未兑现（见 §8）。

### 5.5 工具安全链（纵深防御，6 层）

```
输入：PromptInjectionDetector.checkInput（8 高危 + 5 中危正则；主链路高危拦截/中危告警）
  ↓
工具执行 ToolExecutor 五段管线：
  ① SecurityChain.check：SchemaValidator（自研 JSON Schema 递归校验）
     → PromptInjectionDetector（工具参数路径，高危/中危均拦截）
     → SsrfValidator（主机白名单默认空=拒绝全部 + 内网/回环封禁 + 重定向逐跳校验 maxRedirects=5）
     → CodeScannerCheck（Python 18 危险模式 + Shell 16 命令 + 敏感路径/环境变量）
  ② PermissionService.evaluate（allow/deny/requireApproval）
  ③ 执行分派：代码 Tool > MCP（McpServerManager 连接缓存）> Sandbox（code_execute）> HTTP POST
  ④ 输出截断（maxOutputSize=10KB）
  ⑤ AuditService.recordToolExecution
  ↓
输出：OutputSanitizer.checkOutput（手机/邮箱/身份证/银行卡 PII 脱敏）
```

### 5.6 事件协议（AgentEvent）

- **基础 8 种**：status / thinking / content / tool_call / tool_executing / tool_result / done / error
- **阶段二扩展 7 种**（budget_warning / budget_exceeded / confidence_check / verification_result / reflection / experience_saved / failure_recorded——后 5 个目前为协议预留，仅 budget_exceeded、reflection 实际发射）
- **编排层 13 种字面量**（graph_start / node_start / node_complete / graph_complete / handoff / approval_required / approval_result / message / goal_start / goal_decomposed / goal_complete / agent_timeout / agent_deadlock——未提为常量）
- 映射链：`AgentEvent → AgentChunk → ChatStreamEvent` 两级转换后经 SSE 输出；前端 ProcessTracker 消费同一协议。

### 5.7 LLM 层

- 三级查找的 LlmClientRegistry：静态 Bean → 动态缓存 → LlmProvider SPI 拉配置动态 new OpenAiCompatibleClient（含 evictClient 失效）；
- OpenAiCompatibleClient：覆盖一切 OpenAI 兼容厂商；**reasoning_content 与 content 分离**（适配 DeepSeek-R1 等推理模型）；流式 function calling 增量累积；usage 统计；
- 已知缺陷：同步超时硬编码 120s（配置项 `agent.engine.llm.requestTimeout` 未消费）；content 为空时回退取 reasoning_content 当正式回复（同步模式下可能把思考当答案）；**Message 模型无 toolCalls 字段 → 请求体中 assistant 消息缺 tool_calls 声明，对严格校验协议顺序的供应商可能 400**（详见 §8 D-2）。

### 5.8 记忆体系

- 短期：SessionContextService SPI（默认 50 条，`defaultHistoryLimit` 硬编码）；
- 长期：MemoryStore 四类记忆（semantic 领域知识/episodic 执行事件/procedural 技能/parametric 用户偏好 + experience）；向量生成由实现方负责（MemoryFragment.vector 预留）；
- 写入时机：任务成功后自动沉淀「用户:…(500) \n AI:…(1000)」为 episodic 片段（失败静默）；
- 读取：MemoryRouter.inject 在每次消息构建后注入。

---

## 六、会话功能深度剖析

### 6.1 数据模型（6 实体 / 6 表）

| 表 | 实体 | 状态 |
|----|------|------|
| `session` | Session（30+ 字段：type/status/parentId 分叉树/agent 绑定/model JSON/成本五字段 tokens_*/summary_*/version 乐观锁） | 🟢 使用中 |
| `session_message` | SessionMessage（messageType 8 种/metadata JSON/replyTo/mentionUserIds/**clientId 幂等 ID（未使用）**/seq/**uk(session_id,seq) 唯一键**/edited） | 🟢 使用中 |
| `session_member` | SessionMember（role/lastReadAt/isMuted） | 🟢 只读使用 |
| `part` | Part（消息分段 text/code/tool_use/tool_result/thinking/image） | 🔴 **空壳**（OpenCode 迁移占位，仅 Mapper 壳无业务调用） |
| `session_input` | SessionInput（prompt JSON/delivery=steer/queue/resume） | 🔴 **空壳** |
| `session_context_epoch` | SessionContextEpoch（baseline/snapshot/baselineSeq） | 🔴 **空壳**（未实现 OpenCode 式上下文纪元机制） |

> 领域层为**贫血模型**：@TableName + @Data 纯实体，无业务方法、无 Repository 接口（应用层直接注入 Mapper）——与 DDD 名义不符，实为「分层 CRUD」架构。

### 6.2 应用服务清单

| 服务 | 关键方法 | 说明 |
|------|---------|------|
| SessionService | createSession / createChatSession / getSession（成员校验）/ listSessions / listMySessions / updateSession / deleteSession（逻辑删除）/ getSessionMembers | CRUD 完整 |
| MessageService | sendMessage（seq=messageCount+1 非原子 + SseEventManager 广播）/ listMessages / getMessage / editMessage / deleteMessage / searchMessages（LIKE） | **与 AI 主链路独立**（AI 对话不走此类） |
| SessionContextService | getContext / buildContextMessages（压缩主逻辑）/ compressContext / addToContext / appendChatInteraction（AI 交互落库，selectMaxSeq+1） | 核心枢纽 |
| ContextCompressor | compress（llm 摘要 qwen-plus ≤800 token / truncate 回退；hash 缓存 2h） | 上下文压缩 |

### 6.3 SSE 双通道架构

| 通道 | 实现 | 用途 |
|------|------|------|
| **对话流通道** | AiChatController：StreamingResponseBody + 手工 flush + CountDownLatch | AI 流式回复（content/thinking/tool_*/file 等事件直写响应流） |
| **会话广播通道** | SseController → SseEventManager（ConcurrentHashMap<sessionId, List<SseEmitter>>，无超时） | 协作消息广播（message 事件）、HITL 审批推送（approval_required 事件） |

配套安全细节：SecurityConfig 设置 `shouldFilterAllDispatcherTypes(false)`，避免 SSE 的 ASYNC dispatch 阶段重复鉴权导致 response already committed。

### 6.4 与 agent-engine 的集成

- `DbSessionContextServiceAdapter`（@ConditionalOnProperty `agent.engine.adapter.enabled=true`）把业务侧 SessionContextService 桥到引擎 SPI，覆盖 NoOp 默认；
- AgentExecutionEngine.buildTask：agentId 解析（会话绑定回退）→ Agent 加载 → provider/model 解析 → AgentTask；
- 事件回流：AgentEvent → AgentChunk → ChatStreamEvent 两级映射，Controller 直写 SSE；**HITL 是唯一走 SseEventManager 广播通道的例外**；
- 执行后：引擎自动 storeExperience/语义缓存/recordMetric；AgentExecutionService 执行记录由独立 REST API 管理（不在聊天主链路自动落库——执行记录与对话消息两套账本分离）。

### 6.5 功能实现矩阵（会话域）

| 功能 | 状态 | 备注 |
|------|------|------|
| 创建/重命名/删除/成员查看 | 🟢 | 删除未级联清理 message/member（依赖查询过滤） |
| 归档 | 🟡 | 仅能改 status=2；timeArchived 字段无人写入、无专门端点 |
| 分享 | 🔴 | shareUrl/slug/isPublic 字段在，无生成逻辑 |
| 置顶 | 🔴 | 无 pinned 字段 |
| 成员管理（加人/踢人/退出） | 🔴 | 仅 GET members |
| 标题自动生成 | 🔴 | 固定「新对话」 |
| 消息编辑/删除/搜索 | 🟢 | LIKE 模糊搜索 |
| 消息重发/重新生成 | 🔴 | 无 regenerate 端点 |
| 会话分支（parentId 分叉） | 🔴 | 字段+索引在，无创建分支代码 |
| @提及/引用回复 | 🟡 | 数据层支持（replyTo/mentionUserIds），无未读/提醒逻辑（lastReadAt 未用） |

### 6.6 并发与幂等（当前最大工程缺口之一）

1. **seq 双策略不一致**：MessageService.sendMessage 用 `messageCount+1`（读改写非原子）；appendChatInteraction 已改为 `selectMaxSeq()+1`（注释明确为修补前者），但 MAX(seq) 本身仍非原子——高并发仍会撞 `uk(session_id,seq)` 唯一键且无重试；
2. **无分布式锁**：session 域全代码无 Redisson 锁/synchronized；表有 version 字段但实体未加 @Version；
3. **幂等未落地**：client_id 幂等字段 DDL 声明了、实体有了、但 SendMessageCommand 不含且从不查重——`/chat/stream` 重放会重复调 LLM 并重复落库两条消息；
4. **messageCount/lastMessageAt 竞态**：应用层读改写互相覆盖；
5. **异常路径丢消息**：SSE 出错时（errorRef 非空）已消耗 token 的交互不持久化。

---

## 七、架构评审与风险评估（ATAM）

| # | 风险 | 等级 | 影响 | 缓解措施建议 |
|---|------|------|------|-------------|
| R1 | **agent-engine 零测试**（10,101 行含并发/递归流式/预算熔断的核心引擎，28 个测试文件全在其它模块） | 🔴 高 | 引擎回归无防护网；并发缺陷（messages synchronized + 递归 concatWith）易在生产爆发 | 最高优先补测（见 §9 短期） |
| R2 | **LLM 协议缺陷**：Message 无 toolCalls 字段，多轮工具调用的 assistant tool_calls 不序列化进请求体 | 🔴 高 | 对严格校验的供应商（如 OpenAI 官方、部分闭源网关）可能 400，多轮工具对话中断 | 补字段 + LlmRequestBodyBuilder 序列化 + 回归验证 |
| R3 | **会话并发竞态 + 幂等缺失**（§6.6） | 🔴 高 | 并发对话消息丢失/唯一键冲突；接口重放重复扣费落库 | selectMaxSeq 改 DB 原子方案 + clientId 幂等 |
| R4 | **105 文件未提交变更悬空**（Vite→Next 迁移痕迹 + 引擎认知架构） | 🟠 中 | 不可回溯、协作冲突、CI 构建的代码与磁盘不一致 | 立即分主题拆分提交 |
| R5 | **编排引擎节点类型半占位**：TOOL/ROUTER/PARALLEL/MERGE/SUBGRAPH 无执行器；pause/resume/cancel 存根 | 🟠 中 | 编排能力宣称与实际不符；前端「暂停/恢复」按钮对接空实现 | 补齐或降级宣称 |
| R6 | **认知能力收益未验证**：ReflexionRuntime 简化、DualSystemRouter 不切换运行时、Wenshi PoC 自述「接近纯 LLM」 | 🟠 中 | 四层认知架构可能只产生日志与延迟，不产生质量收益 | A/B 实验门控（见 §9 中期） |
| R7 | **可观测与成本闭环断点**：OTel 默认关闭、RocketMQ 配置未用、DomainEventPublisher 零调用方、agent_execution 记录不自动落库 | 🟠 中 | 生产排障与成本核算能力打折 | 逐项接通或裁剪 |
| R8 | **双引擎/双工作流/双 API 封装并存**（legacy vs wenshi、workflow vs orchestration、axios vs authFetch） | 🟡 低-中 | 认知负担、双倍维护 | 设收敛期限（绞杀者时间表） |
| R9 | **硬编码参数**：LLM 超时 120s、时间预算 300s、心跳 60s、历史 50 条、L1/L3 配额倍率均不可配置 | 🟡 低 | 运维调优需发版 | 提为 AgentEngineProperties |
| R10 | **部署链不完整**：K8s 仅单体后端（gateway/sandbox/前端无清单）、GitHub CI 部署阶段 echo 占位、jmx 压测脚本缺失、prod compose YAML 缩进瑕疵 | 🟡 低 | 「清单齐全」与「一键可部署」之间有差距 | 补齐 Helm 化 |
| R11 | **死代码堆积**：part/session_input/session_context_epoch 三套空壳、CacheKeys.session()、SseEventManager.sendToUser、gewu-desktop 空目录、domain 空包 | 🟡 低 | 认知噪音 | 决策：实现或删除 |

**ATAM 清单结论**：功能需求覆盖度 ★★★★☆（SDLC 全域覆盖）；非功能（安全）★★★★☆（纵深防御出色）；非功能（可测试性）★★☆☆☆（最大短板）；架构可演进性 ★★★★☆（SPI 设计优秀）；决策记录完整性 ★★★★★（docs 体系罕见地与代码同步）；团队能力匹配 ★★★★☆；成本可控性 ★★★☆☆（双 LLM 引擎 + 双存储 + 未用的 MQ）。

---

## 八、技术债清单（按优先级排序）

| 优先级 | 编号 | 债务 | 位置/证据 |
|--------|------|------|----------|
| P0 | D-1 | 引擎零测试 | gewu-agent-engine 无 src/test；SsrfValidator 留了 setHostResolver 测试钩子却无测试 |
| P0 | D-2 | Message 缺 toolCalls 字段 → 请求体协议缺陷 | llm/model/Message.java + LlmRequestBodyBuilder |
| P0 | D-3 | seq 非原子 + 幂等未落地 | MessageService.sendMessage vs SessionContextService.appendChatInteraction |
| P1 | D-4 | OrchestrationEngine.pause/resume/cancel 空存根 | orchestration/OrchestrationEngine.java |
| P1 | D-5 | ReflexionRuntime 简化（maxReflectionRounds 仅出现在 metadata） | orchestration/runtime/ReflexionRuntime.java |
| P1 | D-6 | DualSystemRouter 产出 runtimeMode/modelTier 仅日志 | cognition/DualSystemRouter.java |
| P1 | D-7 | SseMcpClient 名为 SSE 实为 JSON-RPC POST；endpointUrl 死字段；缺 notifications/initialized 握手 | mcp/SseMcpClient.java |
| P1 | D-8 | ToolConfigSource.loadToolsByAgent 默认实现 bug（agentId 与 toolName 相等比较，语义不通） | tool/ToolConfigSource.java |
| P1 | D-9 | PromptInjectionDetector.check 抛裸 SecurityException（与 AgentEngineException 约定不一致）；两条路径拦截策略不一致 | tool/security/PromptInjectionDetector.java |
| P2 | D-10 | requestTimeout 配置项未消费（硬编码 120s）；BudgetController/LifecycleManager 硬编码参数 | OpenAiCompatibleClient / budget / lifecycle |
| P2 | D-11 | content 为空回退取 reasoning_content 当答案 | OpenAiCompatibleClient.parseResponse |
| P2 | D-12 | DebateModeHandler.resolveJudge 连续两次 .metadata() 后者覆盖前者 | orchestration/mode/DebateModeHandler.java |
| P2 | D-13 | 编排层 13 种事件为字面量未提常量；阶段二 5 种事件常量是协议预留未发射 | core/event/AgentEvent.java |
| P2 | D-14 | McpServerManager/SseMcpClient 私 new ObjectMapper 未复用 Spring Bean | mcp/ |
| P2 | D-15 | 同步路径 budget 未随模型路由结果调整；流式 messages 并发保护脆弱 | ReactAgentExecutor |
| P3 | D-16 | part/session_input/session_context_epoch 三空壳 + 5 处死代码 + 空包 | 见 R11 |
| P3 | D-17 | RocketMQ 脚手架无消费者；DomainEventPublisher 零调用 | infrastructure/event/ |
| P3 | D-18 | AgentToolController 缺 DELETE；归档/分享/分支/标题生成/重发未实现 | 见 §6.5 |
| P3 | D-19 | 前端 mock 残留/硬编码用户名/双轨 API 封装/slices 空目录 | gewu-web |
| P3 | D-20 | CI 部署占位 + jmx 缺失 + prod compose 瑕疵 + desktop 空壳 | deploy/ |

---

## 九、优化建议

> 所有建议预留 10% 调整空间（依实际压测/实验结果微调）；优先级基于「影响 × 紧迫性」矩阵。

### 9.1 短期（1-2 个月）——止血与加固

1. **【R4】提交悬空变更**：按主题拆分（Next 迁移 / 引擎认知架构 / 监控调整）分批 commit，恢复可回溯性。
2. **【D-2】修复 LLM 协议缺陷**：Message 增加 toolCalls 字段，LlmRequestBodyBuilder 序列化 assistant.tool_calls；对 OpenAI/DeepSeek/智谱三家做多轮工具调用回归验证。这是**正确性缺陷**，优先于一切性能优化。
3. **【D-1】引擎补测**：优先覆盖 ReactAgentExecutor（同步/流式/工具并行/预算熔断/缓存命中五类场景）、BudgetController 阈值、SecurityChain 五组件、SsrfValidator（已有测试钩子）。目标：核心包行覆盖 ≥70%。
4. **【D-3】消息 seq 原子化 + 幂等**：
   - seq：`UPDATE session SET message_count = message_count + 1 WHERE id = ?` 返回受影响行 + 悠久化唯一键兜底重试（3 次）；或直接用 `uk(session_id, seq)` 冲突重试；
   - 幂等：ChatRequest/SendMessageCommand 增加 clientId，落库前 `SELECT ... WHERE client_id = ?` 查重（client_id 列已存在）。
5. **【D-8/D-9/D-12】三个确定性 bug 修复**：ToolConfigSource 过滤逻辑、PromptInjectionDetector 异常类型与策略统一、DebateModeHandler metadata 覆盖。
6. **【D-10】硬编码参数配置化**：requestTimeout 生效、时间预算/心跳/历史条数/L 级配额倍率提为 `agent.engine.*` 属性。

### 9.2 中期（3-6 个月）——收敛与兑现

1. **【R6】认知收益实验门控**：建立 A/B 评测管道（EvaluationController 已有 LLM-as-Judge + SPC 基础），对四组配置（纯 ReAct / +复杂度路由 / +双系统路由实际切换 / +Reflexion 真反思）跑固定题集，**用数据决定 D-5/D-6 是补全还是裁剪**——避免为架构完整性付出无收益的延迟与 token 成本。
2. **【R8】双轨收敛时间表**：
   - 引擎：wenshi 路由实验结论出来后 3 个月内定胜负（legacy 或 wenshi 退役）；
   - 工作流：明确「workflow=人工审批流、orchestration=Agent DAG」的边界并写进 docs，或启动合并；
   - 前端：API 封装统一到 request.ts。
3. **【R5】编排能力补齐或诚实降级**：优先实现 ROUTER（条件路由）与 PARALLEL/MERGE（并行扇出/汇聚）三类高频节点；pause/resume/cancel 依托 ExecutionRecord 状态机落地（V26 表已建）；短期内在 API 文档标注「暂未实现」。
4. **【D-7】MCP 客户端升级**：实现 Streamable HTTP 传输 + initialized 握手 + resources/prompts 能力（当前仅 tools）；SseMcpClient 重命名或重写。
5. **【R7】可观测闭环**：OTel 导出在生产默认开启（Jaeger 已部署）；agent_execution 在主链路自动落库（当前需手动调 REST）；RocketMQ 要么接入第一个真实事件（如会话创建/Agent 执行完成 → 通知/统计解耦），要么从 compose 与 pom 移除。
6. **【D-18】会话增值功能补全**（按用户价值排序）：标题自动生成（首轮对话后异步 LLM 生成）→ 消息重新生成 → 归档端点 + timeArchived → 分享链接（slug 已有唯一索引）→ 置顶 → 会话分支（parentId 树已就绪）。
7. **【D-16】死代码清理**：part/session_input/session_context_epoch 三选一——若计划实现 OpenCode 式分段消息与上下文纪元则排期，否则删表删实体；sendToUser 修复语义或删除。

### 9.3 长期（6-12 个月）——演进与规模化

1. **多实例与水平扩展验证**：当前 K8s deployment 已是 2 副本 + HPA，但 SseEventManager 是**进程内存连接表**——多副本下 HITL 审批与协作广播会路由到错误实例。方案：SSE 广播改 Redis Pub/Sub（DragonflyDB 已就绪）或改走网关粘性路由；这是上生产多副本的**前置条件**。
2. **领域层充血化**：Session/Requirement 等核心聚合逐步引入业务不变式与领域事件（当前贫血模型使所有规则散落在 24,740 行的 application 层）；配合 RocketMQ 真正启用。
3. **Wenshi 记忆体系深化**：参数化记忆/知识图谱落地（当前设计稿）；经验蒸馏管道（ExperienceDistillationPipeline）接 CognitiveScheduler 定时任务形成自进化闭环（阶段五路线）。
4. **成本治理**：Session 已有 cost/tokens_* 五字段但无写入——把 LlmResponse.usage 回填会话成本统计，打通 UsagePage 与预算控制的闭环；语义缓存命中率、模型路由降级率纳入 agent-dashboard。
5. **部署成熟度**：Helm Chart 化（含 gateway/sandbox/前端）；GitHub CI 部署阶段真实化；jmeter 脚本按 PERFORMANCE-TEST-PLAN.md 落地（750 并发目标先跑通 50 并发 SSE 场景验证首字节 <500ms）。
6. **gewu-desktop 决策**：Electron 壳 0 代码——要么立项（复用 gewu-web 渲染层 + 本地沙箱联动是差异化价值），要么删目录止损。

---

## 十、扩展路线图

```
2026 Q3（短期·止血）        2026 Q4（中期·收敛）           2027 H1（长期·演进）
─────────────────          ─────────────────            ─────────────────
✅ 提交悬空变更              认知 A/B 实验定去留            多副本 SSE 广播改造
✅ LLM 协议缺陷修复          双引擎/双工作流收敛            领域充血化 + 事件驱动
✅ 引擎测试 ≥70%             ROUTER/PARALLEL/MERGE 节点    Wenshi 图谱记忆/自进化
✅ seq 原子化 + 幂等          MCP Streamable HTTP          成本闭环 + 预算联动
✅ 3 个确定性 bug             会话增值功能（标题/重发/分享）   Helm 化 + 压测落地
✅ 参数配置化                 可观测闭环（OTel/MQ/执行账本）  Desktop 立项或下线
                            死代码清理
```

**新能力扩展方向建议**（依平台定位推导）：
- **多模态输入**：Part 表的 image 类型与 SessionMessage.messageType=image 已预留，补 OCR/视觉理解工具即可支持截图问答（开发协作高频场景）；
- **Agent 订阅与计费**：AgentMarket 已有发布/安装/审核闭环，扩展 usage 计量计费（usage 字段体系已备）；
- **技能生态**：SkillController + agent-sdlc-skills 资产（23 技能 md）天然可演化为「技能市场」，与 RoleRegistry 12 角色打通形成 SDLC Agent 商店；
- **开放 API**：agent-engine 已是可独立复用的框架 jar，抽离为独立仓库开源是低成本高回报的品牌扩展（先补测试，见 D-1）。

---

## 十一、结论

**格物平台是一个架构设计意识远超平均水平的单人体/小团队项目**：

1. **架构层面**（置信度：高）：模块化单体 + DDD 分层 + SPI 化引擎库的组合恰当；agent-engine 的 21 个 SPI + NoOp 默认实现 + 26 个业务适配器的「框架/业务分离」设计、六层工具安全纵深、四维预算控制、双闭环验证，达到了商用 Agent 平台的架构完整度。文档体系（41 篇设计文档与代码强同步、每次架构决策有动机注释）是显著亮点。

2. **功能层面**（置信度：高）：17 个业务域、33 个 Controller、26 个前端页面的 SDLC 全流程覆盖，核心链路（登录→会话→SSE 流式对话→工具调用→HITL→持久化回放）端到端闭环可用。认知型 Agent 五阶段计划已完成四阶段。

3. **主要短板**（置信度：高）：① 核心引擎零测试；② LLM 多轮工具调用存在协议级正确性缺陷；③ 会话并发/幂等防护缺失；④ 认知层的实际收益未经实验验证（Reflexion 简化、双系统路由未接线、Wenshi PoC 效果≈纯 LLM）；⑤ 编排节点类型与生命周期管理半占位；⑥ 部署「清单齐全但链路断点」。

4. **总评**：**设计完成度 > 验证完成度**。项目的下一阶段主题不应是「加更多能力」，而是**「验证已建成的能力」**——测试、实验、压测、收敛。按 §9 短期清单执行 1-2 个月即可消除全部 P0 正确性风险；中期以 A/B 实验决定认知层去留后，平台将具备真正的生产就绪条件。

---

*报告完 | 分析工具：codebase-memory 知识图谱 + 4 路并行代码探索 + 关键链路人工验证 | 后续步骤建议见 §9-§10*
