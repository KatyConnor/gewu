# 格物 Agent 引擎 — 架构

> 本篇刻画框架的整体分层、模块依赖、核心数据流、包结构以及 Spring Boot 自动装配机制。
> 包根：`com.gewu.agent.engine`，模块：`com.gewu:gewu-agent-engine`。

## 1. 分层视图

框架自上而下分为六个层次。左侧是**使用方接入面**，越往右越偏**基建与外部资源**：

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         交互/接入层 (使用方)                               │
│   AgentEngine.execute(task)  /  executeStream(task)  ->  Flux<AgentEvent> │
└───────────┬───────────────────────────────────────────────┬──────────────┘
            │                                               │
            ▼                                               ▼
┌───────────────────────┐                       ┌───────────────────────────┐
│   编排层           │                       │   核心引擎层               │
│ orchestration        │  --包含/委托-->        │   core                    │
│ OrchestrationEngine  │                       │   AgentExecutor           │
│ Orchestrator         │                       │   ReactAgentExecutor      │
│ 4 种ModeHandler      │                       │   (ReAct + 工具并行)       │
│ 3 种AgentRuntime     │                       │                           │
│ AutonomousExecutor   │                       │                           │
└───────┬───────────────┘                       └───────────┬───────────────┘
        │                                                   │
        │                                               委托
        ▼                                                   ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                                 能力层                                    │
│  llm (LlmClient/Registry/OpenAiCompatible)    tool (Tool/ToolExecutor)   │
│  message (MessageBuilder/PromptDirective)      mcp (McpClient/Manager)    │
│  cognition (ReasoningKernel/Reflection)       memory (MemoryStore/Router)│
│  hitl (HitlGateway)                          role (RoleRegistry 12角色) │
└───────────────────────────────────┬──────────────────────────────────────┘
                                    │ 依赖
                                    ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                          SPI 契约层 (业务扩展面)                          │
│  spi: PersistenceService/PermissionService/AuditService/                 │
│       SessionContextService/SandboxExecutor/ApiKeyDecryptor              │
│  + 模型: AgentSpec/ToolConfig/ExecutionRecord/PermissionResult/ExecResult│
│  + defaults: NoOp* 全部默认实现                                           │
│  + tool/llm/mcp/role/memory/hitl 各层各自的 ConfigSource/Provider SPI     │
└───────────────────────────────────┬──────────────────────────────────────┘
                                    │ 由使用方实现
                                    ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                          基础设施层 (使用方提供)                           │
│  数据库 · 缓存 · 权限系统 · 审计系统 · 沙箱执行器 · 配置中心 · LLM 供应商  │
└──────────────────────────────────────────────────────────────────────────┘
```

要点：

- **交互层**只暴露 `AgentEngine`（单 Agent）与 `OrchestrationEngine`（多 Agent）两个门面，使用方无需感知内部组件。
- **核心引擎层**与**编排层**是同级的两个执行域：单 Agent 走 `AgentExecutor`，多 Agent 走 `Orchestrator`；编排层的 `AGENT` 节点内部委托 `AgentExecutor` 完成单点推理。
- **能力层**是框架自带的实现集合，本身不持有业务状态，只通过 SPI 向下取数。
- **SPI 契约层**是引擎与业务的边界：框架定义接口并提供 NoOp 默认实现，使用方按需注册 Bean 覆盖。
- **基础设施层**完全在框架之外，由使用方自行选择并实现 SPI。

## 2. 模块依赖关系图

```
                           AgentEngine (core)
                                 │
                ┌────────────────┼────────────────┐
                ▼                ▼                ▼
        AgentExecutor    AgentEngineConfig   (orchestration 委托)
        (ReactAgentExecutor)  │
                │              │
   ┌────────────┼──────────┬───┴────────┬─────────────┐
   ▼            ▼          ▼            ▼             ▼
LlmClientRegistry  ToolExecutor  MessageBuilder  SessionContextSvc  PersistenceSvc
   │                 │              │                 │                 │
   ├──LlmClient      ├──ToolRegistry │                 └──(SPI)          └──(SPI)
   │  └─OpenAiCompatible └─SecurityChain                    
   │      └─LlmRequestBodyBuilder     └─SchemaValidator/SsrfValidator/CodeScanner
   │                                   └─McpServerManager
   │   └─LlmProvider (SPI)                └─McpClient(Stdio/Sse)
   │                                       └─PermissionService/SandboxExecutor/AuditService (SPI)
   │
   └─model: LlmRequest/LlmResponse/LlmChunk/Message/ToolDefinition/ToolCall
```

依赖方向遵循"高层依赖低层抽象、低层不反向依赖"。`llm`、`tool`、`message`、`mcp` 等能力层互不依赖；它们共同依赖 `llm.model`、`spi`（模型定义）以及 `core.event` 等共享模型。`orchestration` 层横向依赖 `core`（`AgentExecutor` / `AgentTask` / `AgentEvent`），但 `core` 不感知 `orchestration`。

## 3. 核心数据流

### 3.1 同步执行流程

```
使用方                  AgentEngine           ReactAgentExecutor        LlmClientRegistry      LlmClient         ToolExecutor
  │  execute(task)          │                       │                          │                  │                    │
  │ ─────────────────────> │                       │                          │                  │                    │
  │                         │ execute(task)         │                          │                  │                    │
  │                         │ ────────────────────> │ getClient(provider)      │                  │                    │
  │                         │                       │ ────────────────────────> │                  │                    │
  │                         │                       │ <──── client ──────────── │                  │                    │
  │                         │                       │ chat(LlmRequest) ──────────────────────────> │                    │
  │                         │                       │ <──── LlmResponse ─────────────────────────  │                    │
  │                         │                       │   若 toolCalls 非空:                                                │
  │                         │                       │     parallel execute(name,args,ctx) ────────────────────────────> │
  │                         │                       │     <──── List<ToolResult> ────────────────────────────────────── │
  │                         │                       │     回灌 tool 消息 → 进入下一轮                                       │
  │                         │                       │   若 toolCalls 为空:  返回                                            │
  │                         │ <── LlmResponse ──── │                                                                       │
  │ <── LlmResponse ─────── │                                                                                              │
```

### 3.2 流式执行流程

```
使用方           AgentEngine      ReactAgentExecutor           LlmClient           Flux<AgentEvent>
  │ executeStream   │                   │                          │                       │
  │ ─────────────> │  executeStream     │                          │                       │
  │                │ ────────────────> │  chatStream(LlmRequest)  │                       │
  │                │                   │ ────────────────────────> │                       │
  │                │                   │ <──── Flux<LlmChunk> ───── │                       │
  │                │                   │  map chunk -> AgentEvent                          │
  │                │                   │    reasoning? -> THINKING  ───────────────────> │ ──> 使用方实时消费
  │                │                   │    delta?     -> CONTENT   ───────────────────> │
  │                │                   │    toolCallDelta? 累积                             │
  │                │                   │  流结束: 累积完毕 toolCalls                          │
  │                │                   │    有工具: TOOL_CALL -> TOOL_EXECUTING -> 并行执行   │
  │                │                   │             -> TOOL_RESULT -> concatWith 递归下一轮 │
  │                │                   │    无工具: DONE                                     │
  │                │ <──── Flux<AgentEvent> ────────────────────────────────────────────── │
  │ <────────────── │                                                                          │
```

### 3.3 编排执行流程

```
使用方            OrchestrationEngine         Orchestrator            ModeHandler          AgentRuntime -> AgentExecutor
  │  executeStream(graph,ctx) │                    │                       │                      │
  │ ───────────────────────> │  orchestrator.run   │                       │                      │
  │                          │ ──────────────────> │ 按 mode 选 Handler     │                      │
  │                          │                     │ ────────────────────> │ 遍历节点:             │
  │                          │                     │                       │   AGENT? → roleCode  │
  │                          │                     │                       │     -> resolve AgentRoleSpec
  │                          │                     │                       │     -> AgentRuntime.execute(task)
  │                          │                     │                       │        -> AgentExecutor.executeStream  ─> ↓
  │                          │                     │                       │   TOOL?   -> ToolExecutor.execute       │
  │                          │                     │                       │   HUMAN? -> HitlGateway                │
  │                          │                     │                       │   ROUTER?-> 条件分支                 │
  │                          │                     │  发布 graph_start/node_start/node_complete/handoff/graph_complete
  │ <──── Flux<AgentEvent>（编排层扩展事件，nodeId/role/metadata 携带上下文）────────────────── │
```

## 4. 包结构树

```
com.gewu.agent.engine
├── AgentEngineException                 统一异常（含 code）
├── core
│   ├── AgentEngine                      门面
│   ├── AgentExecutor                    执行器接口
│   ├── ReactAgentExecutor               ReAct 默认实现
│   ├── AgentTask                        执行任务
│   ├── AgentEngineConfig                引擎配置
│   └── event/AgentEvent                 流式事件 + 事件类型常量
├── llm
│   ├── LlmClient                        客户端接口
│   ├── LlmClientRegistry               注册中心/多供应商路由
│   ├── LlmProvider                      供应商动态配置 SPI
│   ├── LlmProviderConfig                供应商配置 record
│   ├── OpenAiCompatibleClient           OpenAI 兼容通用实现
│   ├── LlmRequestBodyBuilder            请求体构建器
│   └── model/                           LlmRequest LlmResponse LlmChunk Message ToolDefinition ToolCall
├── tool
│   ├── Tool                             工具接口
│   ├── ToolProvider                     @ToolProvider 注解
│   ├── ToolRegistry                     代码工具注册中心
│   ├── ToolExecutor                     执行器(安全管线)
│   ├── ToolContext                      执行上下文
│   ├── ToolResult                       执行结果
│   ├── ToolConfigSource                 配置源 SPI
│   ├── NoOpToolConfigSource             NoOp
│   └── security/                        SecurityCheck SecurityChain SchemaValidator
│                                       SsrfValidator CodeScanner DefaultCodeScanner
├── message
│   ├── MessageBuilder                   SPI
│   ├── DefaultMessageBuilder            默认实现
│   ├── SystemPromptComposer             System Prompt 组装器
│   └── PromptDirective                  agentMode/thinkingStyle 指令 + 温度映射
├── mcp
│   ├── McpClient / StdioMcpClient / SseMcpClient
│   ├── McpServerManager / McpServerConfigSource / NoOpMcpServerConfigSource
│   └── McpServerDescriptor / McpToolDefinition / McpToolResult
├── orchestration
│   ├── OrchestrationEngine / Orchestrator / GoalPlanner / DefaultGoalPlanner / AutonomousExecutor
│   ├── model/                            OrchestrationGraph GraphNode GraphEdge NodeType GraphType
│   │                                     OrchestrationMode ExecutionMode OrchestrationContext
│   │                                     OrchestrationResult AutonomousGoal
│   ├── mode/                             ModeHandler PipelineModeHandler SupervisorModeHandler
│   │                                     SwarmModeHandler DebateModeHandler
│   ├── runtime/                          AgentRuntime ReactRuntime PlanExecuteRuntime ReflexionRuntime
│   └── role/                             AgentRoleSpec CapabilityCard RoleRegistry RoleConfigSource NoOpRoleConfigSource
├── hitl                                  HitlGateway ApprovalRequest HumanDecision NoOpHitlGateway
├── memory                                MemoryStore MemoryRouter MemoryFragment NoOp*
├── cognition                             ReasoningKernel EvolutionHook ReflectionEngine ReasoningResult NoOp*
├── spi                                   AgentSpec ToolConfig ExecutionRecord PermissionResult ExecResult
│   │   PersistenceService PermissionService AuditService SessionContextService
│   │   SandboxExecutor ApiKeyDecryptor
│   └── defaults/                         全部 NoOp 实现
└── config
    ├── AgentEngineAutoConfiguration      自动装配（@Configuration）
    └── AgentEngineProperties             配置属性（prefix=agent.engine）
```

## 5. Spring Boot 自动装配说明

框架通过 `AgentEngineAutoConfiguration`（`@Configuration` + `@EnableConfigurationProperties(AgentEngineProperties.class)`）完成全部装配。装配策略是**"默认值 + `@ConditionalOnMissingBean` 兜底"**：每一个 Bean 都带 `@ConditionalOnMissingBean`，使用方注册同名/同类型 Bean 即覆盖默认实现。

装配分组与顺序（按 `AgentEngineAutoConfiguration` 中的注释分区）：

| 分区 | 装配内容 | 覆盖方式 |
|------|----------|----------|
| 基础设施 | `llmHttpClient`(HttpClient) / `LlmRequestBodyBuilder` / `LlmProvider`(NoOp) / `LlmClientRegistry` | 注入业务侧的 `List<LlmClient>` 即纳入静态注册 |
| 工具层 | `SsrfValidator` / `CodeScanner`(Default) / `SchemaValidator` / `SecurityChain` / `ToolRegistry`(收集 `List<Tool>`) / `ToolConfigSource`(NoOp) / `McpServerConfigSource`(NoOp) / `McpServerManager` / `ToolExecutor` | 注册 `@ToolProvider` 即被 `ToolRegistry` 收集；注册自定义 `SecurityCheck` 即被 `SecurityChain` 收集 |
| SPI 默认实现 | `PersistenceService` / `PermissionService` / `AuditService` / `SessionContextService` / `SandboxExecutor` / `ApiKeyDecryptor` 全部 NoOp | 实现对应接口并注册 Bean 即覆盖 |
| 消息构建 | `SystemPromptComposer` / `MessageBuilder`(Default) | 可替换 `MessageBuilder` SPI |
| 核心引擎 | `agentToolExecutor`(线程池, name=agentToolExecutor) / `AgentEngineConfig` / `AgentExecutor`(React) / `AgentEngine` | 线程池核心/最大/队列容量来自属性；可注册自定义 `AgentExecutor` |
| 记忆与认知 | `MemoryStore` / `MemoryRouter` / `ReasoningKernel` / `EvolutionHook` / `ReflectionEngine` 全部 NoOp | 同 SPI 覆盖模式 |
| 编排引擎 | `RoleConfigSource`(NoOp) / `RoleRegistry`(合并外部+内置12角色) / `HitlGateway`(NoOp) / `Orchestrator` / `GoalPlanner` / `AutonomousExecutor` / `OrchestrationEngine` | 注册 `AgentRoleSpec` Bean 或实现 `RoleConfigSource` 即扩展角色 |

`AgentEngineProperties`（前缀 `agent.engine`）关键配置项：

```yaml
agent:
  engine:
    engine:
      max-tool-rounds: 10          # 单次执行最大工具调用轮次
      default-max-tokens: 8192
      default-temperature: 0.7
      tool-executor-core-pool-size: 8
      tool-executor-max-pool-size: 16
      tool-executor-queue-capacity: 100
    llm:
      connect-timeout: 30s
      request-timeout: 120s
    tool:
      allowed-hosts: ""            # HTTP 工具主机白名单(逗号分隔)，默认空=拒绝全部外部
      max-redirects: 5
      max-output-size: 10240       # 10KB
      default-timeout-seconds: 30
```

线程池装配细节：`agentToolExecutor` 使用 `ThreadPoolExecutor`，核心/最大/队列容量由属性驱动，拒绝策略为 `CallerRunsPolicy`，线程为 daemon，名称 `agent-tool-exec`，销毁方法 `shutdown`。

关键设计含义：

- **"零配置可启动"**：不实现任何 SPI、不注册任何 Bean，框架也能跑通——所有依赖都走 NoOp，`AgentEngine.execute` 会在解析供应商/加载工具时抛出带错误码的 `AgentEngineException`，但装配本身不会失败。
- **"按需替换逐层增强"**：使用方从最小可用集开始，逐步按业务需要实现 SPI（先 `PersistenceService` + `LlmProvider`，再 `PermissionService`/`AuditService`，最后工具与编排），每一步都是注册 Bean 而非改框架。
- **"静态 + 动态双轨"**：`LlmClientRegistry` 同时支持静态注入的 `LlmClient` Bean 与通过 `LlmProvider` SPI 动态创建的客户端；`RoleRegistry` 合并代码注册的 `AgentRoleSpec` Bean 与 `RoleConfigSource` 加载的外部角色，并保留 12 个内置默认角色作为兜底。

---

上一篇：[01-overview.md](./01-overview.md) ｜ 下一篇：[03-core-engine.md](./03-core-engine.md)