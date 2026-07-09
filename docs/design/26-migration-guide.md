# 格物平台 OpenCode 迁移指南 V1.0

## 文档信息

| 项目 | 内容 |
|------|------|
| 文档名称 | 格物平台 OpenCode 迁移指南 |
| 版本 | V1.0 |
| 创建日期 | 2026-07-08 |
| 文档状态 | 初稿 |
| 源系统 | opencode-1.17.14 (Bun/TypeScript/Effect/SolidJS/SQLite) |
| 目标系统 | gewu-platform (JDK 21/Spring Boot/React/OceanBase) |
| 参考文档 | 19-gap-analysis.md, 20-unified-prd.md, 21-unified-architecture.md, 22-unified-db-schema.md, 23-unified-api-spec.md, 24-unified-security.md, 25-unified-deployment.md |

---

## 目录

1. [迁移总览](#1-迁移总览)
2. [六阶段迁移路线图](#2-六阶段迁移路线图)
3. [关键迁移模式](#3-关键迁移模式)
4. [数据迁移脚本](#4-数据迁移脚本)
5. [兼容层策略](#5-兼容层策略)
6. [测试策略](#6-测试策略)
7. [风险与缓解](#7-风险与缓解)
8. [回滚方案](#8-回滚方案)
9. [附录](#9-附录)

---

## 1. 迁移总览

### 1.1 OpenCode → 格物 技术栈对比与迁移策略

| 技术维度 | OpenCode 1.17.14 | 格物平台 | 迁移复杂度 | 迁移策略 |
|---------|-----------------|---------|-----------|---------|
| 运行时 | Bun 1.3.14 (JavaScript) | JDK 21 (Java) | **高** | Redesign — 语言栈完全替换 |
| 框架 | Effect 4.0.0-beta.83 (函数式) | Spring Boot 3.x + Spring Cloud (OOP) | **高** | Redesign — 范式迁移 |
| 前端 | SolidJS 1.9.10 + OpenTUI (CLI/TUI/Web) | React 18 + TypeScript + Ant Design | **中** | Replace — 重写 UI |
| 数据库 | SQLite + Drizzle ORM | OceanBase 4.2+ / 达梦 8 / 人大金仓 8 + MyBatis-Plus | **中** | Replace — 数据迁移 |
| 缓存 | 无 | DragonflyDB | **低** | Add — 新增组件 |
| 消息队列 | 无 | RocketMQ | **低** | Add — 新增组件 |
| HTTP 框架 | Hono (TypeScript) | 自研 API Gateway (Java/Netty) | **高** | Redesign — 信创要求 |
| AI 集成 | Vercel AI SDK 6.0.168 | 自研 AI 集成层 | **中** | Redesign — 信创适配 |
| 流式传输 | Effect Stream (SSE) | Spring WebFlux / Netty | **中** | Replace — 框架替换 |
| 工具协议 | MCP (Model Context Protocol) | 自研工具协议 | **高** | Redesign — 信创合规 |
| 权限模型 | 3 模式 (allow/deny/ask) | RBAC + 工具级权限 | **中** | Upgrade — 概念升级 |
| 插件系统 | Plugin System + MCP | 工作流引擎 + 工具注册 | **高** | Redesign — 架构升级 |
| 配置中心 | 文件/环境变量 | Nacos Config | **低** | Replace — 平台替换 |
| 部署 | SST Serverless / Docker | K8s + Jenkins CI/CD | **中** | Replace — 企业级 |
| 安全 | 基础沙箱 | 等保 2.0 三级 + 国密 SM2/SM3/SM4 | **高** | Upgrade — 全面增强 |
| 监控 | Sentry + OpenTelemetry | Nightingale + Prometheus + Grafana | **中** | Replace — 平台替换 |

### 1.2 迁移复杂度分布

| 复杂度等级 | 数量 | 占比 | 典型项 |
|-----------|------|------|--------|
| **低** (Direct Mapping) | 3 | 18% | 配置中心、缓存、消息队列 |
| **中** (Replace with Adapt) | 8 | 50% | 数据库、前端、流式传输、部署、监控 |
| **高** (Redesign Required) | 5 | 32% | 运行时、框架、工具协议、插件系统、网关 |

### 1.3 迁移工作量估算

| 阶段 | 周期 | 人月 | 主要产出 |
|------|------|------|---------|
| Phase 1: 数据模型迁移 | 2 周 | 2 人月 | DDL 脚本、数据迁移工具、校验脚本 |
| Phase 2: 业务逻辑迁移 | 4 周 | 6 人月 | 6 个 Java 模块、Spring 服务 |
| Phase 3: API 迁移 | 2 周 | 3 人月 | REST 控制器、WebFlux 流式端点 |
| Phase 4: 前端迁移 | 3 周 | 4 人月 | React 页面、状态管理、路由 |
| Phase 5: MCP 替换 | 3 周 | 4 人月 | 自研工具协议、SDK、注册中心 |
| Phase 6: 集成与测试 | 2 周 | 3 人月 | 集成测试报告、性能报告、安全审计报告 |
| **合计** | **16 周** | **22 人月** | 全量迁移交付 |

### 1.4 架构对应关系

```
OpenCode                         格物平台
────────                         ────────
31 packages (Bun workspaces)     7 Java modules + 9 business modules
├── Session                       ├── gewu-session (Java)
│   ├── SessionV2                 │   ├── SessionService
│   ├── SessionInput              │   ├── SessionInputService
│   └── SessionContextEpoch       │   └── ContextService
├── Agent (agent-def, agent-hub)  ├── gewu-agent
│   ├── AgentDefinition           │   ├── AgentService
│   └── AgentHub                  │   └── AgentRepository
├── Tool (tool-def, tool-hub)     ├── gewu-tool
│   ├── ToolDefinition            │   ├── ToolRegistry
│   └── ToolExecution             │   └── ToolExecutor
├── MCP (mcp-client, mcp-sdk)     ├── gewu-tool (自研协议)
│   ├── MCP Transport             │   ├── ToolProtocolHandler
│   └── MCP Tool Discovery        │   └── ToolDiscoveryService
├── Permission (authorization)    ├── gewu-security
│   ├── PermissionEvaluator       │   ├── PermissionService
│   └── Sandbox                   │   └── SandboxManager
├── Config (config)               ├── gewu-gateway (Nacos)
│   └── ConfigLoader              │   └── NacosConfigAdapter
├── Server (server)               ├── gewu-gateway + gewu-server
│   └── Hono HTTP                 │   └── Spring REST + Netty Gateway
├── Project (project)             ├── gewu-project
│   ├── ProjectDirectory          │   ├── ProjectService
│   └── ProjectManager            │   └── DirectoryService
├── Event (event, event-v2)       ├── gewu-common (audit_log)
│   └── EventSequence + Event     │   └── AuditLogService
├── Plugin System                 ├── gewu-workflow
│   └── PluginLoader              │   └── WorkflowEngine
├── Frontend (SolidJS/TUI)        └── gewu-frontend (React)
    └── UI Components                 └── Ant Design Components
```

---

## 2. 六阶段迁移路线图

### Phase 1: 数据模型迁移 (2 周)

**目标**: 将 OpenCode 11 张核心表迁移至 Gewu 数据库 Schema，完成 DDL 创建和初始数据导入。

**关键任务**:

| 任务 | 负责人 | 工期 | 交付物 |
|------|--------|------|--------|
| 1.1 Schema 映射审查 | DBA + 后端 | 2d | 映射确认矩阵 |
| 1.2 DDL 迁移脚本编写 | DBA | 3d | 26 张表 DDL (OceanBase) |
| 1.3 数据类型转换工具 | 后端 | 2d | 转换工具 + 验证脚本 |
| 1.4 新增表创建 (part/session_input/session_context_epoch/project_directory) | DBA | 2d | 4 张新增表 DDL |
| 1.5 数据导入与校验 | DBA + QA | 2d | 数据校验报告 |
| 1.6 索引与性能优化 | DBA | 1d | 索引策略文档 |

**数据类型转换规则**:

| OpenCode 类型 | 格物目标类型 | 转换规则 |
|---------------|-------------|---------|
| `TEXT PRIMARY KEY` | `VARCHAR(26) PRIMARY KEY` | 生成 ULID，保留原有 mapping 表 |
| `TEXT NOT NULL` | `VARCHAR(255) NOT NULL` | 短文本直接映射 |
| `TEXT (JSON)` | `JSON` | SQLite JSON TEXT → OceanBase JSON |
| `REAL` | `DECIMAL(10,4)` | 浮点转定点，需精度验证 |
| `INTEGER (小整数)` | `TINYINT` | 布尔/状态字段 |
| `INTEGER (时间戳ms)` | `BIGINT` | 毫秒时间戳 |
| `BLOB` | `LONGBLOB` | 二进制数据 |

**SQLite 特有模式迁移注意事项**:
- SQLite 无独立 BOOLEAN 类型 (映射为 TINYINT)
- SQLite 无 CHECK 约束 → 在应用层添加 Bean Validation
- SQLite 无时区处理 → OceanBase 统一使用 UTC BIGINT
- SQLite 外键默认不启用 → OceanBase 启用严格外键检查
- SQLite AUTOINCREMENT → OceanBase 使用 ULID 生成器

### Phase 2: 业务逻辑迁移 (4 周)

**目标**: 将 OpenCode 6 个核心模块从 TypeScript/Effect 迁移至 Java/Spring。

**迁移顺序**:

```
Week 1: Config + Project    (最少外部依赖)
Week 2: Permission + Tool   (核心依赖)
Week 3: Session + Agent     (最复杂模块)
Week 4: 集成验证 + Bug 修复
```

**模块迁移详情**:

| 模块 | 源文件 | 目标包 | 关键类 | 复杂度 |
|------|--------|--------|--------|--------|
| Config | packages/config | gewu-common | ConfigService, NacosConfigAdapter | 低 |
| Project | packages/project | gewu-project | ProjectService, DirectoryService | 低 |
| Permission | packages/authorization | gewu-security | PermissionService, PermissionEvaluator | 中 |
| Tool | packages/tool-def, tool-hub | gewu-tool | ToolRegistry, ToolExecutor, ToolDefinitionService | 中 |
| Session | packages/session-v2 | gewu-session | SessionService, SessionInputService, ContextEpochService | 高 |
| Agent | packages/agent-def, agent-hub | gewu-agent | AgentService, AgentExecutionService, AgentConfigService | 高 |

**Effect → Spring 迁移模式对照**:

| Effect 模式 | Spring 等价 | 迁移策略 |
|------------|------------|---------|
| `Effect<A, E, R>` | `Service + 异常` | R=依赖 → 构造注入; E=异常 → 受检异常; A=返回值 |
| `pipe(effect, Effect.map, Effect.flatMap)` | `Mono/Flux.flatMap` | 链式调用替换; 或 Virtual Thread 同步方式 |
| `Layer<A, E, R>` | `@Configuration + @Bean` | DI 容器替换 |
| `Effect.try(() => ...)` | `try/catch + @Transactional` | 错误处理 |
| `Effect.sync` | `CompletableFuture.supplyAsync` | 异步执行 |
| `Effect.sleep` | `Thread.sleep` (VT) / `Mono.delay` | 延时操作 |
| `Stream.pipe(Stream.map, Stream.runCollect)` | `Flux.map.collectList()` | 流式处理 |
| `Schema.decodeUnknown` | `@Valid + jakarta.validation` | 验证替换 |
| `Context.GenericTag<R>` | `@Autowired / @Resource` | 依赖注入 |

**推荐策略**: 优先使用 Virtual Thread (虚拟线程) 简化迁移。在 Spring Boot 3.x + JDK 21 环境下，大部分 Effect 的 flatMap 链可以转换为同步的 try/catch + 虚拟线程，避免 WebFlux 的复杂性。仅在需要 SSE 流式传输的场景使用 WebFlux。

```
Effect chain:
  pipe(
    loadConfig,
    Effect.flatMap(validateConfig),
    Effect.flatMap(createSession),
    Effect.map(formatResult)
  )

Spring (Virtual Thread) sync:
  Config config = loadConfig();           // 同步调用
  validateConfig(config);                 // 同步校验
  Session session = createSession(config);// 同步创建
  return formatResult(session);           // 同步返回
```

### Phase 3: API 迁移 (2 周)

**目标**: 将 Hono HTTP 路由转换为 Spring REST Controller，迁移流式 SSE 传输。

**路由迁移对照表**:

| OpenCode (Hono) | 格物 (Spring) | 方法 | 说明 |
|-----------------|--------------|------|------|
| `GET /api/v1/sessions` | `GET /api/v1/sessions` | `@GetMapping` | 会话列表 |
| `POST /api/v1/sessions` | `POST /api/v1/sessions` | `@PostMapping` | 创建会话 |
| `GET /api/v1/sessions/:id` | `GET /api/v1/sessions/{id}` | `@GetMapping` | 获取会话 |
| `DELETE /api/v1/sessions/:id` | `DELETE /api/v1/sessions/{id}` | `@DeleteMapping` | 删除会话 |
| `POST /api/v1/sessions/:id/prompt` | `POST /api/v1/sessions/{id}/prompt` | `@PostMapping` | 发送提示 |
| `GET /api/v1/sessions/:id/messages` | `GET /api/v1/sessions/{id}/messages` | `@GetMapping` | 消息列表 |
| `GET /api/v1/agents` | `GET /api/v1/agents` | `@GetMapping` | Agent 列表 |
| `POST /api/v1/agents` | `POST /api/v1/agents` | `@PostMapping` | 创建 Agent |
| `POST /api/v1/tools/:id/execute` | `POST /api/v1/tools/{id}/execute` | `@PostMapping` | 执行工具 |
| `POST /api/v1/auth/login` | `POST /api/v1/auth/login` | `@PostMapping` | 登录 |
| `POST /api/v1/auth/refresh` | `POST /api/v1/auth/refresh` | `@PostMapping` | 刷新令牌 |
| `GET /api/v1/projects` | `GET /api/v1/projects` | `@GetMapping` | 项目列表 |
| `POST /api/v1/projects` | `POST /api/v1/projects` | `@PostMapping` | 创建项目 |

**SSE 流式传输迁移**:

```
OpenCode (Effect Stream + Hono SSE helper):
  app.get("/api/v1/sessions/:id/stream", async (c) => {
    const stream = SessionV2.promptStream(sessionId, input)
    return c.stream(stream.pipe(
      Stream.map(encodeSSE),
      Stream.encode(),
    ))
  })

格物 (Spring WebFlux):
  @GetMapping("/api/v1/sessions/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<String>> streamPrompt(@PathVariable String id, @RequestBody PromptRequest request) {
    return sessionService.promptStream(id, request)
      .map(result -> ServerSentEvent.<String>builder()
        .data(jsonMapper.toJson(result))
        .build());
  }
```

**错误处理迁移**:

| OpenCode (Effect) | 格物 (Spring) |
|------------------|--------------|
| `Effect.catchAll` → 类型化错误 | `@ExceptionHandler` + `ErrorCode` |
| `Effect.catchTags` | `ResponseStatusException` |
| `Effect.catchIf` | 条件 `@ExceptionHandler` |
| `Either` → OK/Error 联合 | `ResponseEntity<T>` + `ErrorResponse` |
| `Option` → 可能缺失 | `Optional<T>`, `@Nullable` |
| `Effect.withSpan` (Tracing) | `@Observed / Micrometer` |

### Phase 4: 前端迁移 (3 周)

**目标**: 将 SolidJS + OpenTUI 界面迁移至 React 18 + Ant Design。

**注意**: 格物平台定位为 Web 界面（不包含 TUI/CLI），因此 CLI/TUI 部分不在迁移范围内。

**前端迁移映射**:

| OpenCode (SolidJS) | 格物 (React) | 迁移策略 |
|-------------------|-------------|---------|
| 组件函数 + JSX | 函数组件 + JSX/TSX | 1:1 重写 (语法差异小) |
| `createSignal` | `useState` | 替换 |
| `createEffect` | `useEffect` | 替换 |
| `createMemo` | `useMemo` | 替换 |
| `onMount` | `useEffect([], [])` | 替换 |
| `For` / `Show` / `Switch` | `.map()` / 三元 / `switch` | 替换 |
| `<Resource>` + Suspense | `useSWR` / `@tanstack/react-query` | 重写 |
| Effect Layer (依赖注入) | Redux Toolkit / Zustand | 重写 |
| Store (SolidJS) | Zustand store | 替换 |
| OpenTUI 组件 | Ant Design 组件 | 完全重写 |
| TUI 终端交互 | Web 交互模式 | 重新设计 |
| Effect Stream (SSE) | EventSource + React | 桥接模式 |
| Hono 客户端 | Axios / React Query | 替换 |

**状态管理迁移**:

```
SolidJS (Effect Layer):
  const SessionService = Context.GenericTag<SessionService>()
  const session = yield* SessionService.getSession(id)

React + Zustand:
  const session = useSessionStore((state) => state.sessions[id])
  useEffect(() => {
    sessionService.getSession(id).then(setSession)
  }, [id])
```

**页面迁移清单**:

| 页面/组件 | OpenCode 路径 | 格物路径 | 优先级 |
|-----------|--------------|---------|--------|
| 会话列表 | TUI/CLI 主界面 | `/sessions` | P0 |
| 会话详情 | TUI 聊天视图 | `/sessions/:id` | P0 |
| Agent 配置 | config CLI | `/agents` | P0 |
| 工具管理 | tool CLI | `/tools` | P1 |
| 项目管理 | project CLI | `/projects` | P1 |
| 设置页面 | config CLI | `/settings` | P1 |
| 登录/注册 | 无 (格物新增) | `/login`, `/register` | P0 |
| 用户管理 | 无 (格物新增) | `/admin/users` | P1 |
| 工作流设计器 | 无 (格物新增) | `/workflows` | P2 |
| 监控面板 | 无 (格物新增) | `/monitoring` | P1 |

### Phase 5: MCP 替换 (3 周)

**目标**: 用自研工具协议替代 MCP，满足信创合规要求。

**MCP 功能映射**:

| MCP 概念 | 自研工具协议 | 说明 |
|---------|------------|------|
| MCP Transport (stdio/SSE/HTTP) | 自研传输层 (国密 TLS + SM2 签名) | 移除 stdio (CLI 不再需要)，保留 SSE + HTTP |
| MCP Tool Discovery | 工具注册中心 (Nacos Service) | 从文件发现变为服务注册发现 |
| MCP Tool Call | ToolExecutor + 协议路由 | 保持接口语义兼容 |
| MCP Resource | 工具元数据 + OpenAPI | 资源模型替代 |
| MCP Prompt Template | Agent 提示模板管理 | 概念升级 |
| MCP Server | 工具提供者服务 | 架构一致 |

**MCP 传输模式替换**:

| MCP 模式 | 替换方案 | 信创适配 |
|---------|---------|---------|
| stdio Transport | **废弃** (格物无 CLI) | — |
| SSE Transport | 自研 SSE + 国密 WebSocket | 国密 TLS 1.3 + SM2 证书 |
| HTTP Transport | 自研 HTTP + Netty 网关 | SM3 签名 + SM4 载荷加密 |

**自研工具协议设计要点**:
1. **协议格式**: JSON-RPC 2.0 基础上增加国密签名字段 (`sm2Signature`, `sm3Digest`)
2. **认证**: 使用 SM2 证书进行双向认证 (mTLS)
3. **工具发现**: 通过 Nacos 注册中心，支持按标签/分类查询
4. **调用链路**: 网关 → 工具注册中心 → 工具执行器 → 沙箱
5. **兼容性**: 提供 OpenCode MCP API 的 v0 兼容层 (见第 5 章)

**MCP 迁移步骤**:

```
Week 1: 协议设计与 SDK 开发
  ├── 定义自研工具协议规范 (JSON + 国密)
  ├── 开发 Java SDK (协议编解码 + 签名验证)
  └── 开发 TypeScript SDK (前端兼容)

Week 2: 工具注册中心 + 执行器
  ├── 实现 ToolRegistryService (Nacos 集成)
  ├── 实现 ToolExecutor (沙箱集成)
  └── 实现协议路由 (网关插件)

Week 3: 兼容层 + 集成测试
  ├── 实现 MCP v0 兼容 API
  ├── OpenCode 现有工具迁移适配
  └── 集成测试与性能基准
```

### Phase 6: 集成与测试 (2 周)

**目标**: 全链路集成验证，性能基准测试，安全合规审计。

**测试矩阵**:

| 测试类型 | 范围 | 工具 | 验收标准 |
|---------|------|------|---------|
| 单元测试 | 全部 Java 模块 | JUnit 5 + Mockito | 覆盖率 ≥ 80% |
| 集成测试 | 跨模块流程 | Spring Boot Test + Testcontainers | 全部通过 |
| API 兼容性 | 迁移 API vs 原始 API | Postman Newman | 100% 兼容 |
| 数据一致性 | 迁移后数据校验 | 自定义校验脚本 | 数据零丢失 |
| 性能基准 | 迁移前后对比 | JMeter + Prometheus | QPS ≥ 原系统 80% |
| 安全审计 | 全系统 | SAST/DAST + 人工审查 | 等保 2.0 三级 |
| 信创合规 | 全系统 | 合规检查清单 | 通过率 100% |

**性能基准指标**:

| 指标 | OpenCode 基线 | 格物目标 | 临界值 |
|------|-------------|---------|--------|
| 会话创建 P99 | 50ms | ≤ 100ms | 200ms |
| 提示发送 P99 | 200ms | ≤ 300ms | 500ms |
| SSE 流式延迟 | 150ms | ≤ 200ms | 400ms |
| 工具执行 P99 | 500ms | ≤ 800ms | 1500ms |
| 并发会话数 | 10 | ≥ 200 (设计目标 500) | 100 |
| API 吞吐量 | 100 QPS | ≥ 500 QPS | 200 QPS |

---

## 3. 关键迁移模式

### 3.1 Effect → Spring 范式迁移

**核心差异: 函数式 vs 面向对象**

| 维度 | Effect (函数式) | Spring (面向对象) |
|------|---------------|-----------------|
| 状态管理 | 不可变状态 + Ref | 可变对象 + Repository |
| 错误处理 | 类型化错误 (Either) | 异常 + 全局处理器 |
| 依赖注入 | Layer + Context.Tag | @Autowired + 构造注入 |
| 副作用 | Effect 类型显式声明 | 隐式副作用 + @Transactional |
| 并发 | Fiber + 结构化并发 | Virtual Thread / CompletableFuture |
| 流式处理 | Stream (push-based) | Flux (reactive) / 分页 |
| 配置 | Schema + ConfigLoader | @ConfigurationProperties |

**模式 1: flatMap 链 → 同步方法调用**

```
// Effect (TypeScript):
pipe(
  Effect.flatMap(getSession),     // 加载会话
  Effect.flatMap(validateAccess), // 权限验证
  Effect.flatMap(loadMessages),   // 加载消息
  Effect.map(formatResponse)      // 格式化响应
)

// Spring (Java):
// 使用虚拟线程，保持同步风格
Session session = getSession(id);           // 加载会话
validateAccess(session, userId);            // 权限验证
List<Message> messages = loadMessages(id);  // 加载消息
return formatResponse(session, messages);   // 格式化响应
```

**模式 2: Effect 错误处理 → Spring 异常体系**

```
// Effect — 类型化错误:
pipe(
  validatePermission(input),
  Effect.catchTags({
    NotFoundError: () => Effect.succeed(null),
    PermissionDenied: () => Effect.fail(new AuthError("..."))
  })
)

// Spring — 异常:
try {
  return validatePermission(input);
} catch (NotFoundException e) {
  return null;
} catch (PermissionDeniedException e) {
  throw new AuthException("...");
}
```

**模式 3: Layer 依赖注入 → Spring DI**

```
// Effect Layer:
const SessionLive = Layer.effect(
  SessionService,
  SessionService.of({ ... })
)
Effect.provide(sessionFlow, SessionLive)

// Spring DI:
@Service
@RequiredArgsConstructor
public class SessionService {
  private final ToolService toolService;
  private final PermissionService permissionService;
}
```

**模式 4: Stream + SSE → WebFlux Flux**

```
// Effect Stream:
Stream.fromIterable(events).pipe(
  Stream.map(encodeSSE),
  Stream.runForEach(sendChunk)
)

// Spring WebFlux:
Flux.fromIterable(events)
  .map(this::encodeSSE)
  .doOnNext(this::sendChunk)
```

**模式 5: Fiber + structured concurrency → Virtual Thread**

```
// Effect Fiber:
pipe(
  Effect.fork(computeTaskA),
  Effect.flatMap((fiberA) =>
    pipe(
      Effect.fork(computeTaskB),
      Effect.flatMap((fiberB) =>
        pipe(
          Fiber.join(fiberA),
          Effect.zip(Fiber.join(fiberB))
        )
      )
    )
  )
)

// Spring Virtual Thread:
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
  Future<ResultA> futureA = executor.submit(this::computeTaskA);
  Future<ResultB> futureB = executor.submit(this::computeTaskB);
  return new CombinedResult(futureA.get(), futureB.get());
}
```

### 3.2 Event Sourcing → Audit Log

OpenCode 使用 Event 系统 (event_sequence + event 表) 实现事件溯源。格物平台使用 RocketMQ + audit_log 表。

**事件映射**:

| OpenCode Event | 格物 AuditLog | 说明 |
|---------------|--------------|------|
| `session.created` | `audit_log (action='CREATE', resource='session')` | 直接映射 |
| `message.sent` | `audit_log (action='CREATE', resource='message')` + 消息表 | 冗余存储 |
| `tool.executed` | `audit_log (action='EXECUTE', resource='tool')` | 直接映射 |
| `agent.modified` | `audit_log (action='UPDATE', resource='agent')` | 直接映射 |
| `permission.evaluated` | **不迁移** (内部事件) | 仅在日志级别保留 |

**迁移策略**:
1. 从 event_sequence 表读取历史事件
2. 按 `event_type` 分类转换为 audit_log 行
3. RocketMQ 消费端负责新事件的实时审计
4. OpenCode 的事件驱动通知 → 格物使用 RocketMQ 事务消息

### 3.3 MCP → 自研工具协议

**协议栈对比**:

```
MCP Protocol Stack:
┌──────────────────┐
│    MCP Client    │
├──────────────────┤
│  MCP Transport   │ ← stdio / SSE / HTTP
├──────────────────┤
│    JSON-RPC 2.0  │
├──────────────────┤
│  TLS (optional)  │
└──────────────────┘

自研工具协议:
┌─────────────────────┐
│  Tool SDK (Java/TS) │
├─────────────────────┤
│  自研传输层          │ ← 国密 TLS + SSE / HTTP
├─────────────────────┤
│  JSON-RPC 2.0 + 国密 │ ← SM2 签名, SM3 摘要
├─────────────────────┤
│  国密 TLS 1.3        │ ← SM2 证书, SM4 加密
└─────────────────────┘
```

**协议替换原则**:
1. **语义兼容**: 工具发现、调用、返回的 JSON-RPC 消息格式保持兼容
2. **传输替换**: stdio 移除，SSE/HTTP 增加国密层
3. **注册替换**: 文件发现 → Nacos 服务发现
4. **认证替换**: 无认证 → 双向 SM2 证书认证

### 3.4 Session V2 架构迁移

**核心概念迁移**:

| OpenCode SessionV2 | 格物 | 说明 |
|-------------------|------|------|
| Session (parent_id, version, agent, model, share_url) | `session` 表 (继承 + 增强) | 增加 teamScope, tags |
| SessionInput (prompt, queue, delivery mode) | `session_input` 表 | 直接迁移 |
| SessionContextEpoch (context snapshot) | `session_context_epoch` 表 | 直接迁移 |
| SessionExecution (process-local coordinator) | `session_execution` 服务 + 分布式锁 | 分布式替代进程本地 |
| SessionStore | `SessionRepository` (MyBatis-Plus) | ORM 替换 |
| SessionRunCoordinator (join same-session) | 分布式协调 (Redis + RocketMQ) | 分布式协调 |
| 投影/回溯 (projected history) | 查询 + 缓存 (DragonflyDB) | 缓存加速 |

**Session V2 状态机迁移**:

```
OpenCode (in-memory state):
  State: idle → prompting → running → draining → idle

格物 (DB + Redis):
  session.status: IDLE → PROMPTING → RUNNING → DRAINING → IDLE
  + 分布式锁防止并发 Session 冲突
  + DragonflyDB 缓存活跃 Session 上下文
```

### 3.5 Plugin System → Workflow Engine

**架构升级**:

| 维度 | OpenCode Plugin | 格物 Workflow |
|------|----------------|-------------|
| 粒度 | 包级 (package) | 节点级 (node) |
| 组合 | 插拔式 | 编排式 (DAG) |
| 执行 | Effect 链 | 状态机 + 事务 |
| 存储 | 文件系统 | DB + 缓存 |
| 配置 | Config Schema | 可视化配置 |
| 监控 | 无 | 运行时指标 |
| 扩缩 | 重新加载 | 动态节点 |

**升级路径**:
1. Phase 1: 将 OpenCode 插件转换为工作流节点 (Tool Node, Agent Node)
2. Phase 2: 实现节点编排 (串行/并行/条件分支)
3. Phase 3: 提供可视化设计器
4. Phase 4: 支持自定义节点开发

---

## 4. 数据迁移脚本

### 4.1 新增表 DDL (OceanBase 兼容)

```sql
-- =============================================================
-- 格物平台 - OpenCode 迁移新增表 DDL
-- 目标数据库: OceanBase 4.2+ (MySQL 兼容模式)
-- 兼容: MySQL 8.0 / 达梦 8.0 / 人大金仓 8.0
-- =============================================================

-- ---------------------------------------------------------
-- 1. part — 消息多部分存储
--    OpenCode: part 表存储消息中的多个独立部分
--    (工具调用结果、代码片段、文件等)
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS `part` (
  `id`              VARCHAR(26)  NOT NULL COMMENT 'ULID 主键',
  `message_id`      VARCHAR(26)  NOT NULL COMMENT '关联消息 ID',
  `session_id`      VARCHAR(26)  NOT NULL COMMENT '关联会话 ID',
  `part_type`       VARCHAR(50)  NOT NULL COMMENT '部分类型: text/tool_call/tool_result/code/file',
  `content`         JSON         NOT NULL COMMENT '部分内容 (JSON)',
  `sequence`        INT          NOT NULL DEFAULT 0 COMMENT '部分序号 (同消息内排序)',
  `parent_part_id`  VARCHAR(26)  NULL     DEFAULT NULL COMMENT '父部分 ID (嵌套结构)',
  `metadata`        JSON         NULL     DEFAULT NULL COMMENT '扩展元数据',
  `created_at`      BIGINT       NOT NULL COMMENT '创建时间 (ms)',
  `updated_at`      BIGINT       NOT NULL COMMENT '更新时间 (ms)',
  `deleted`         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0=正常, 1=删除',
  PRIMARY KEY (`id`),
  INDEX `idx_message_id` (`message_id`),
  INDEX `idx_session_id` (`session_id`),
  INDEX `idx_part_type`  (`part_type`),
  INDEX `idx_sequence`   (`message_id`, `sequence`)
) DEFAULT CHARSET = utf8mb4 COMMENT = '消息多部分存储';

-- ---------------------------------------------------------
-- 2. session_input — AI 会话输入管理
--    OpenCode: SessionInput 持久化 prompt/queue/steer
--    三种交付模式: steer (立即)/queue (排队)/resume (恢复)
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS `session_input` (
  `id`              VARCHAR(26)  NOT NULL COMMENT 'ULID 主键',
  `session_id`      VARCHAR(26)  NOT NULL COMMENT '关联会话 ID',
  `project_id`      VARCHAR(26)  NULL     DEFAULT NULL COMMENT '关联项目 ID',
  `input_type`      VARCHAR(20)  NOT NULL COMMENT '交付模式: steer/queue/resume',
  `content`         JSON         NOT NULL COMMENT '输入内容',
  `status`          VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT '状态: pending/promoted/failed/cancelled',
  `message_id`      VARCHAR(26)  NULL     DEFAULT NULL COMMENT '提升后的消息 ID',
  `priority`        INT          NOT NULL DEFAULT 0 COMMENT '优先级 (数字越小越优先)',
  `retry_count`     INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
  `last_error`      TEXT         NULL     DEFAULT NULL COMMENT '最后错误信息',
  `created_at`      BIGINT       NOT NULL COMMENT '创建时间 (ms)',
  `updated_at`      BIGINT       NOT NULL COMMENT '更新时间 (ms)',
  `created_by`      VARCHAR(26)  NULL     DEFAULT NULL COMMENT '创建人 ID',
  `deleted`         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0=正常, 1=删除',
  PRIMARY KEY (`id`),
  INDEX `idx_session_status` (`session_id`, `status`),
  INDEX `idx_status`         (`status`),
  INDEX `idx_created_at`     (`created_at`),
  INDEX `idx_input_type`     (`input_type`)
) DEFAULT CHARSET = utf8mb4 COMMENT = 'AI 会话输入管理';

-- ---------------------------------------------------------
-- 3. session_context_epoch — AI 上下文快照
--    OpenCode: SessionContextEpoch 持久化上下文状态
--    用于恢复会话、回溯上下文、精确重试
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS `session_context_epoch` (
  `id`              VARCHAR(26)  NOT NULL COMMENT 'ULID 主键',
  `session_id`      VARCHAR(26)  NOT NULL COMMENT '关联会话 ID',
  `epoch_number`    INT          NOT NULL COMMENT 'Epoch 序号 (自增)',
  `context_type`    VARCHAR(50)  NOT NULL COMMENT '上下文类型: system/history/truncated',
  `context_data`    JSON         NOT NULL COMMENT '上下文快照数据',
  `message_count`   INT          NOT NULL DEFAULT 0 COMMENT '该 epoch 包含的消息数',
  `token_count`     INT          NOT NULL DEFAULT 0 COMMENT 'Token 数 (近似)',
  `parent_epoch_id` VARCHAR(26)  NULL     DEFAULT NULL COMMENT '父 epoch ID (回退/分支)',
  `metadata`        JSON         NULL     DEFAULT NULL COMMENT '扩展元数据',
  `created_at`      BIGINT       NOT NULL COMMENT '创建时间 (ms)',
  `deleted`         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0=正常, 1=删除',
  PRIMARY KEY (`id`),
  INDEX `idx_session_id`    (`session_id`),
  INDEX `idx_epoch_number`  (`session_id`, `epoch_number`),
  INDEX `idx_context_type`  (`context_type`),
  UNIQUE KEY `uk_session_epoch` (`session_id`, `epoch_number`)
) DEFAULT CHARSET = utf8mb4 COMMENT = 'AI 上下文快照';

-- ---------------------------------------------------------
-- 4. project_directory — 代码项目目录管理
--    OpenCode: ProjectDirectory 管理项目目录结构
--    包含工作树、VCS 关联、沙箱配置
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS `project_directory` (
  `id`              VARCHAR(26)  NOT NULL COMMENT 'ULID 主键',
  `project_id`      VARCHAR(26)  NOT NULL COMMENT '关联项目 ID',
  `path`            VARCHAR(512) NOT NULL COMMENT '目录路径 (相对于项目根)',
  `directory_type`  VARCHAR(30)  NOT NULL DEFAULT 'source' COMMENT '目录类型: source/output/config/test/other',
  `vcs_type`        VARCHAR(20)  NULL     DEFAULT NULL COMMENT 'VCS 类型: git/svn/none',
  `vcs_url`         VARCHAR(512) NULL     DEFAULT NULL COMMENT 'VCS 远程地址',
  `vcs_branch`      VARCHAR(100) NULL     DEFAULT NULL COMMENT 'VCS 分支/标签',
  `sandbox_config`  JSON         NULL     DEFAULT NULL COMMENT '沙箱配置 (Docker/Firecracker/gVisor)',
  `ignore_patterns` JSON         NULL     DEFAULT NULL COMMENT '忽略模式列表 (glob)',
  `metadata`        JSON         NULL     DEFAULT NULL COMMENT '扩展元数据',
  `created_at`      BIGINT       NOT NULL COMMENT '创建时间 (ms)',
  `updated_at`      BIGINT       NOT NULL COMMENT '更新时间 (ms)',
  `created_by`      VARCHAR(26)  NULL     DEFAULT NULL COMMENT '创建人 ID',
  `deleted`         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0=正常, 1=删除',
  PRIMARY KEY (`id`),
  INDEX `idx_project_id`    (`project_id`),
  INDEX `idx_directory_type` (`directory_type`),
  INDEX `idx_path`          (`project_id`, `path`(255)),
  UNIQUE KEY `uk_project_path` (`project_id`, `path`(255))
) DEFAULT CHARSET = utf8mb4 COMMENT = '代码项目目录管理';

-- =============================================================
-- 审计日志表 (替代 OpenCode EventSequence + Event)
-- =============================================================
CREATE TABLE IF NOT EXISTS `audit_log` (
  `id`              VARCHAR(26)  NOT NULL COMMENT 'ULID 主键',
  `tenant_id`       VARCHAR(26)  NULL     DEFAULT NULL COMMENT '租户 ID',
  `user_id`         VARCHAR(26)  NULL     DEFAULT NULL COMMENT '操作用户 ID',
  `session_id`      VARCHAR(26)  NULL     DEFAULT NULL COMMENT '关联会话 ID',
  `action`          VARCHAR(50)  NOT NULL COMMENT '操作类型: CREATE/READ/UPDATE/DELETE/EXECUTE/LOGIN/LOGOUT',
  `resource_type`   VARCHAR(50)  NOT NULL COMMENT '资源类型: session/message/tool/agent/project/user',
  `resource_id`     VARCHAR(26)  NOT NULL COMMENT '资源 ID',
  `detail`          JSON         NULL     DEFAULT NULL COMMENT '操作详情',
  `ip_address`      VARCHAR(45)  NULL     DEFAULT NULL COMMENT '操作 IP',
  `user_agent`      VARCHAR(255) NULL     DEFAULT NULL COMMENT '用户代理',
  `result`          VARCHAR(10)  NOT NULL DEFAULT 'SUCCESS' COMMENT '结果: SUCCESS/FAILURE/DENIED',
  `duration_ms`     INT          NULL     DEFAULT NULL COMMENT '操作耗时 (ms)',
  `created_at`      BIGINT       NOT NULL COMMENT '创建时间 (ms)',
  PRIMARY KEY (`id`),
  INDEX `idx_user_id`       (`user_id`),
  INDEX `idx_resource`      (`resource_type`, `resource_id`),
  INDEX `idx_action`        (`action`),
  INDEX `idx_session_id`    (`session_id`),
  INDEX `idx_created_at`    (`created_at`),
  INDEX `idx_tenant_time`   (`tenant_id`, `created_at`)
) DEFAULT CHARSET = utf8mb4 COMMENT = '审计日志 (替代 EventSequence + Event)';

-- =============================================================
-- 数据迁移校验视图
-- =============================================================
CREATE OR REPLACE VIEW `v_migration_count` AS
SELECT 'part' AS table_name, COUNT(*) AS row_count FROM part
UNION ALL
SELECT 'session_input', COUNT(*) FROM session_input
UNION ALL
SELECT 'session_context_epoch', COUNT(*) FROM session_context_epoch
UNION ALL
SELECT 'project_directory', COUNT(*) FROM project_directory
UNION ALL
SELECT 'audit_log', COUNT(*) FROM audit_log;
```

### 4.2 数据迁移校验脚本 (伪代码)

```java
// MigrationValidationService.java
// 用于验证 OpenCode → 格物 数据迁移的完整性

@Service
@RequiredArgsConstructor
public class MigrationValidationService {

  private final SessionRepository sessionRepository;
  private final AuditLogRepository auditLogRepository;

  /**
   * 校验迁移后的会话数据完整性
   * 对比: (1) 源 SQLite 导出文件 (2) 目标 OceanBase 数据
   */
  public MigrationReport validateSessionMigration(Path sqliteDumpFile) {
    // Step 1: 解析 SQLite 导出
    List<SessionSnapshot> sourceSessions = parseSqliteDump(sqliteDumpFile);

    // Step 2: 查询目标库
    List<SessionEntity> targetSessions = sessionRepository.selectList(null);

    // Step 3: 逐行对比
    int matched = 0, missing = 0, corrupted = 0;
    for (SessionSnapshot source : sourceSessions) {
      SessionEntity target = targetSessions.stream()
        .filter(t -> t.getId().equals(source.getId()))
        .findFirst()
        .orElse(null);

      if (target == null) {
        missing++;
      } else if (!compareFields(source, target)) {
        corrupted++;
      } else {
        matched++;
      }
    }

    return new MigrationReport(matched, missing, corrupted);
  }

  private boolean compareFields(SessionSnapshot source, SessionEntity target) {
    // 字段级对比: created_at, updated_at, status, agent_id, model, ...
    return Objects.equals(source.getId(), target.getId())
        && Math.abs(source.getCreatedAt() - target.getCreatedAt()) <= 1000  // 容差 1s
        && Objects.equals(source.getStatus(), target.getStatus());
  }
}
```

### 4.3 数据迁移工具命令

```bash
# OpenCode 数据导出 (SQLite → JSON)
sqlite3 ~/.opencode/data.db \
  "SELECT json_object('id', id, 'session_id', session_id, 'created_at', created_at, \
   'part_type', part_type, 'content', content) FROM part;" \
  > /tmp/migration/part_export.json

# JSON → OceanBase 导入 (使用 MyBatis-Plus Batch)
# 在 Java 迁移工具中执行:
# migrationTool --input /tmp/migration/part_export.json \
#               --target oceanbase://host:port/gewu \
#               --table part \
#               --batch-size 1000

# 数据校验
java -jar migration-validator.jar \
  --source /tmp/migration/ \
  --target jdbc:oceanbase://localhost:2883/gewu \
  --output /tmp/migration/validation_report.html
```

---

## 5. 兼容层策略

### 5.1 v0 API 兼容性层

在 API 网关中实现 OpenCode API 的 v0 兼容层，确保迁移期间已有客户端不中断。

**架构**:

```
┌─────────────────────────────────────────────────────────────┐
│                       API Gateway                           │
│  ┌─────────────────┐  ┌──────────────────────────────────┐ │
│  │ v1 Route (默认)  │  │ v0 Compatibility Layer           │ │
│  │ /api/v1/*        │  │ /api/v0/* → translate → /api/v1/ │ │
│  └─────────────────┘  └──────────────────────────────────┘ │
│         │                         │                         │
│         ▼                         ▼                         │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              Backend Services (Spring)               │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

**v0→v1 API 差异处理**:

| 差异点 | v0 (OpenCode) | v1 (格物) | 兼容处理 |
|--------|--------------|----------|---------|
| 认证头 | `Authorization: Bearer <token>` | `Authorization: SM2 <signature>` | 网关转换 |
| 分页参数 | `offset` + `limit` | `page` + `size` | 参数映射 |
| 时间格式 | ISO 8601 string | BIGINT ms | 格式转换 |
| 错误格式 | `{error: string, code: string}` | `{code: int, message: string, detail: object}` | 格式适配 |
| ID 格式 | TEXT UUID | VARCHAR(26) ULID | ID 映射表 |
| SSE 事件 | `data: {...}\n\n` | `data: {...}\n\n` (兼容) | 无变化 |
| 工具调用 | MCP JSON-RPC | 自研协议 JSON-RPC + 国密 | 网关路由 |

**兼容层的实现**:

```java
// CompatibilityFilter.java — API 网关中的 v0 兼容过滤器

@Component
@Order(1)
public class CompatibilityV0Filter implements GatewayFilter {

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    ServerHttpRequest request = exchange.getRequest();
    String path = request.getURI().getPath();

    // 识别 v0 API 请求
    if (path.startsWith("/api/v0/")) {
      // 转换路径 /api/v0/sessions → /api/v1/sessions
      String v1Path = path.replace("/api/v0/", "/api/v1/");

      // 转换认证头
      HttpHeaders headers = convertAuthHeader(request.getHeaders());

      // 转换分页参数
      MultiValueMap<String, String> params = convertPaginationParams(
        request.getQueryParams()
      );

      // 构建新请求
      ServerHttpRequest mutatedRequest = request.mutate()
        .path(v1Path)
        .headers(h -> h.clear())
        .headers(h -> h.addAll(headers))
        .queryParams(params)
        .build();

      return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    return chain.filter(exchange);
  }
}
```

### 5.2 ID 映射表

对于迁移期间需要保持 ID 兼容的场景，维护 ID 映射表：

```sql
CREATE TABLE IF NOT EXISTS `id_mapping` (
  `legacy_id`   VARCHAR(255) NOT NULL COMMENT 'OpenCode 原始 ID',
  `gewu_id`     VARCHAR(26)  NOT NULL COMMENT '格物 ULID',
  `resource_type` VARCHAR(50) NOT NULL COMMENT '资源类型',
  `created_at`  BIGINT       NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`legacy_id`, `resource_type`),
  INDEX `idx_gewu_id` (`gewu_id`)
) DEFAULT CHARSET = utf8mb4 COMMENT = 'OpenCode → 格物 ID 映射表';
```

### 5.3 双写策略

在关键路径上实施双写，确保迁移平滑：

```
┌──────────┐    ┌──────────────┐    ┌──────────┐
│ 客户端    │───▶│ v0 兼容层     │───▶│ v1 服务  │
└──────────┘    └──────┬───────┘    └──────────┘
                       │ 双写
                       ▼
              ┌──────────────────┐
              │ 监控/校验服务      │
              │ (比较 v0 vs v1   │
              │  响应差异)        │
              └──────────────────┘
```

---

## 6. 测试策略

### 6.1 双重运行验证 (Dual-Run Verification)

在完整迁移期间，新旧系统并行运行，通过流量复制对比验证：

```
OpenCode (旧) ◀─── 流量录制 ───▶ 格物 (新)
     │                                  │
     └────────── 响应对比 ──────────────┘
                      │
                      ▼
              差异报告 + 告警
```

**实现方式**:

| 阶段 | 方法 | 工具 |
|------|------|------|
| Phase 1 | SQLite vs OceanBase 数据行级对比 | 自定义校验脚本 |
| Phase 2 | 相同输入 → 对比 Effect vs Spring 输出 | JUnit 参数化测试 |
| Phase 3 | Postman 集合同时在 v0/v1 执行 | Newman + 自定义断言 |
| Phase 4 | 截图对比 (SolidJS vs React) | Playwright Visual Diff |
| Phase 5-6 | 全链路流量录制回放 | 自研流量录制代理 |

### 6.2 A/B 测试

| 模块 | 测试组 A | 测试组 B | 指标 | 切换条件 |
|------|---------|---------|------|---------|
| 会话 | OpenCode Session | 格物 Session | P99 延迟, 成功率 | 延迟 ≤ 1.2x, 成功率 = 100% |
| 工具 | MCP 工具 | 自研协议工具 | 执行延迟, 可用性 | 延迟 ≤ 1.5x, 可用性 ≥ 99.9% |
| 流式 | Effect Stream | WebFlux Flux | 首 token 延迟, 吞吐 | 首 token ≤ 1.2x, 吞吐 ≥ 0.8x |

### 6.3 回归测试清单

| 测试场景 | 输入 | 预期输出 | 验证方式 |
|---------|------|---------|---------|
| 创建会话 | POST /sessions | 201 + session JSON | API 测试 |
| 发送消息 | POST /sessions/:id/prompt | SSE 流式响应 | WebFlux 测试 |
| 执行工具 | POST /tools/:id/execute | 200 + 结果 JSON | 单元测试 |
| 权限评估 | 有/无权限请求 | 200/403 | 安全测试 |
| 会话恢复 | GET /sessions/:id?resume=true | 完整上下文 | 集成测试 |
| 数据迁移 | SQLite dump → OceanBase | 100% 行匹配 | 校验脚本 |
| 并发会话 | 200 同时请求 | 全部成功 | 压力测试 |
| 国密认证 | SM2 签名请求 | 200 | 安全测试 |

### 6.4 自动化测试流水线

```yaml
# Jenkins CI/CD - 迁移测试流水线
stages:
  - name: 单元测试
    script: mvn test -pl gewu-session,gewu-agent,gewu-tool
  - name: 集成测试
    script: mvn verify -P integration-test
    services:
      - oceanbase:4.2
      - dragonflydb
      - rocketmq:5.0
  - name: API 兼容性测试
    script: |
      newman run migration-compatibility.postman_collection.json \
        --env-var "baseUrl=http://localhost:8080/api/v0"
  - name: 压力测试
    script: |
      jmeter -n -t migration-benchmark.jmx \
        -l results.jtl \
        -e -o /tmp/report
    artifacts:
      paths: [/tmp/report]
  - name: 数据一致性校验
    script: java -jar migration-validator.jar --compare-all
```

---

## 7. 风险与缓解

### 7.1 风险矩阵

| ID | 风险描述 | 概率 | 影响 | 等级 | 阶段 | 缓解措施 |
|----|---------|------|------|------|------|---------|
| R1 | **数据丢失/损坏** — SQLite → OceanBase 类型转换导致数据截断 | 中 | 严重 | **高** | P1 | 全量校验、类型映射表、回滚脚本 |
| R2 | **API 不兼容** — v0 兼容层遗漏或行为差异 | 中 | 高 | **高** | P3 | 完整的 Postman 兼容性测试套件 |
| R3 | **性能退化** — Virtual Thread 在高 IO 场景不如 Effect Fiber | 中 | 中 | **中** | P2 | 性能基准、连接池调优、异步回调优化 |
| R4 | **SSE 流式中断** — WebFlux 在高并发下的背压问题 | 低 | 严重 | **中** | P3 | Netty 参数调优、限流熔断 |
| R5 | **MCP 替换兼容** — 现有工具集成方无法及时适配自研协议 | 中 | 高 | **高** | P5 | 提供双协议 (v0 MCP + v1 自研)，逐步弃用 |
| R6 | **国密性能开销** — SM2/SM3/SM4 加解密导致 API 延迟增加 | 中 | 中 | **中** | P5 | 硬件加速 (国密芯片/指令集)、连接复用 |
| R7 | **事务模型差异** — SQLite 单写事务 vs OceanBase 分布式事务 | 中 | 高 | **高** | P1 | 事务边界审查、分布式事务 (Seata) |
| R8 | **人员技能差距** — 团队 Effect/函数式 → Spring OOP 转换能力不足 | 高 | 中 | **高** | P2 | 培训、代码审查、迁移模式文档 |
| R9 | **会话上下文丢失** — SessionContextEpoch 迁移时序不一致 | 低 | 严重 | **中** | P2 | 幂等导入、版本号检测 |
| R10 | **信创认证延迟** — 国产 CPU/OS 适配未完成 | 中 | 严重 | **高** | P5 | 先 x86 部署，后补充信创认证 |

### 7.2 风险缓解详细计划

**R1 数据丢失/损坏**:
- 在迁移前对所有 TEXT→VARCHAR 字段进行长度审计
- 超长文本自动截断并记录日志
- 双通道校验：源数据行数 vs 目标行数，MD5 校验和
- 增量迁移 + 全量校验每 1000 行

**R5 MCP 替换兼容**:
- 网关层同时支持 MCP JSON-RPC 和自研协议
- 提供 MCP→自研协议的适配器 SDK (Java + TypeScript)
- 设定 6 个月的双协议共存期
- 逐步弃用计划: Phase 5 引入自研协议 → Phase 6 标记 MCP deprecated → 6 个月后移除

**R8 人员技能差距**:
- 组织 Effect→Spring 迁移模式工作坊 (Week 0)
- 提供本指南第 3 章的代码模式对照表作为参考
- 关键模块由资深工程师带头 + Code Review
- 建立迁移模式和最佳实践的 Wiki

### 7.3 安全迁移风险

| 风险 | 来源 | 影响 | 缓解 |
|------|------|------|------|
| OpenCode 明文 token 泄露 | 配置迁移 | API Key 泄露 | 迁移前加密 + 目标库 SM4 加密存储 |
| SQLite 无权限控制漏洞 | 数据迁移 | 敏感数据暴露 | 迁移后清理 SQLite 文件 |
| MCP 无认证接口暴露 | 协议替换 | 未授权工具调用 | 自研协议默认双向认证 |
| 遗留 API Key 在代码中 | 代码迁移 | 密钥泄露 | 全仓扫描 + .gitignore 强化 |

---

## 8. 回滚方案

### 8.1 回滚原则

1. **每次迁移操作必须有回滚脚本**
2. **数据库迁移必须在事务中执行**，失败自动回滚
3. **API 兼容层确保客户端无感知回滚**
4. **回滚时间 ≤ 迁移操作时间**

### 8.2 各阶段回滚方案

| 阶段 | 回滚触发条件 | 回滚操作 | RTO | RPO |
|------|------------|---------|-----|-----|
| P1: 数据迁移 | 数据校验失败率 > 1% | 执行 DDL 回滚脚本 + 从 SQLite 备份恢复 | 30 min | 0 (无数据丢失) |
| P2: 业务逻辑 | 核心模块 P99 > 阈值 2x | 回滚 Java 模块到前一版本 + 切换流量 | 15 min | 0 (无状态) |
| P3: API 迁移 | 兼容性测试失败 > 5% | 网关回滚到 v0 路由 + 禁用 v1 路由 | 5 min | 0 (无状态) |
| P4: 前端迁移 | 核心功能无法使用 | CDN 回滚到上一版本 + 禁用新路由 | 10 min | 0 (无状态) |
| P5: MCP 替换 | 工具调用失败率 > 5% | 网关回退到 MCP 协议路由 | 5 min | 0 (无状态) |
| P6: 集成 | 验收测试未通过 | 整体回滚到迁移前基线版本 | 60 min | 0 (无状态) |

### 8.3 数据库回滚 DDL

```sql
-- DDL 回滚脚本: 撤销 Phase 1 新增的表
-- 在 OceanBase 中执行

DROP VIEW IF EXISTS v_migration_count;
DROP TABLE IF EXISTS `audit_log`;
DROP TABLE IF EXISTS `project_directory`;
DROP TABLE IF EXISTS `session_context_epoch`;
DROP TABLE IF EXISTS `session_input`;
DROP TABLE IF EXISTS `part`;
```

### 8.4 回滚决策流程

```
触发回滚告警
    │
    ▼
自动评估: 是否达到回滚阈值?
    │
    ├── 是 → 自动执行回滚脚本
    │        │
    │        ▼
    │   通知迁移负责人 + 发送回滚报告
    │
    └── 否 → 人工评估
             │
             ├── 继续迁移 → 修复问题后继续
             │
             └── 人工回滚 → 执行回滚操作
```

### 8.5 回滚验证

每次回滚后执行以下验证:

1. **数据回滚验证**: 计数对比回滚前后
2. **功能回滚验证**: API 烟雾测试 (smoke test)
3. **性能回滚验证**: P99 延迟对比 ≤ 回滚前 1.2x
4. **安全回滚验证**: 审计日志连续性

---

## 9. 附录

### 9.1 迁移里程碑时间线

```
Week 0: 准备
  ├── 环境搭建 (OceanBase / 中间件)
  ├── 团队培训 (迁移模式工作坊)
  └── 工具链准备 (流量录制 / 校验工具)

Week 1-2: Phase 1 数据模型迁移
  ├── DDL 创建 + 数据导入
  └── [里程碑] 数据校验通过

Week 3-6: Phase 2 业务逻辑迁移
  ├── Config + Project → Permisison + Tool → Session + Agent
  └── [里程碑] 6 个模块全部通过集成测试

Week 7-8: Phase 3 API 迁移
  ├── REST 控制器 + SSE 流式
  └── [里程碑] v0 API 兼容性测试通过

Week 9-11: Phase 4 前端迁移
  ├── React 页面 + 状态管理
  └── [里程碑] 核心页面 E2E 测试通过

Week 12-14: Phase 5 MCP 替换
  ├── 自研协议 SDK + 注册中心 + 兼容层
  └── [里程碑] 工具调用双协议验证通过

Week 15-16: Phase 6 集成与测试
  ├── 全链路压测 + 安全审计
  └── [里程碑] 迁移验收通过 ✓

Week 17: 切换与监控
  ├── 正式切换流量到新系统
  └── [里程碑] 格物平台上线

Week 18-24: 观察期
  ├── 性能监控 + 问题修复
  └── [里程碑] 稳定性确认 + 旧系统关停
```

### 9.2 迁移工具清单

| 工具 | 用途 | 来源 | 开发语言 |
|------|------|------|---------|
| `migration-exporter` | SQLite 数据导出 | 自研 | Go (静态编译) |
| `migration-importer` | OceanBase 批量导入 | 自研 | Java 21 |
| `migration-validator` | 数据一致性校验 | 自研 | Java 21 |
| `traffic-recorder` | 流量录制 (网关插件) | 自研 | Java (Netty) |
| `compat-tester` | API 兼容性测试 | 自研 + Newman | TypeScript |
| `benchmark-runner` | 性能基准测试 | JMeter | Java |
| `schema-diff` | Schema 差异对比 | 自研 | Python |

### 9.3 参考文档

| 文档 | 说明 |
|------|------|
| 19-gap-analysis.md | 差异分析 — 技术栈、功能、安全全面对比 |
| 20-unified-prd.md | 统一 PRD — 用户故事和业务规则 |
| 21-unified-architecture.md | 统一架构 — 分层 + DDD 设计 |
| 22-unified-db-schema.md | 统一数据库 — 26 张表设计 |
| 23-unified-api-spec.md | 统一 API — 11 模块 60+ 端点 |
| 24-unified-security.md | 统一安全 — 等保 2.0 三级 + 国密 |
| 25-unified-deployment.md | 统一部署 — K8s + Jenkins |
| OpenCode 源码 (opencode-1.17.14) | 源系统参考实现 |

### 9.4 术语表

| 术语 | 定义 |
|------|------|
| ULID | Universally Unique Lexicographically Sortable Identifier，全局唯一有序 ID |
| Virtual Thread | JDK 21 虚拟线程，轻量级用户态线程 |
| DDD | Domain-Driven Design，领域驱动设计 |
| SSE | Server-Sent Events，服务器推送事件 |
| MCP | Model Context Protocol，模型上下文协议 |
| 等保 2.0 | 中国网络安全等级保护 2.0 标准 |
| 国密 | 中国商用密码算法标准 (SM2/SM3/SM4) |
| Xinchuang (信创) | 中国信息技术应用创新计划 |
| RTO | Recovery Time Objective，恢复时间目标 |
| RPO | Recovery Point Objective，恢复点目标 |

---

> **文档版本**: V1.0 | **最后更新**: 2026-07-08 | **下一版本**: 根据迁移实践迭代更新
