# 格物 Agent 引擎 — 概述

> 模块坐标：`com.gewu:gewu-agent-engine`，包根 `com.gewu.agent.engine`，111 个源文件，`mvn compile` 通过。
> 本篇为框架技术文档第一篇，覆盖定位、设计理念、能力矩阵、横向对比与适用场景。

## 1. 定位与背景

格物 Agent 引擎（gewu-agent-engine）是从「格物智能体平台」中抽离、沉淀出的独立 Java Agent 引擎框架。它将平台中沉淀下来的 LLM 对接、ReAct 执行、工具体系、多 Agent 编排、安全管控等能力封装为一个**可被任意 Spring Boot 项目引入的 starter**，其目标是：

- 给上层应用提供一套**与具体 LLM 供应商解耦**的 Agent 执行抽象；
- 给业务团队提供一套**以 SPI 为扩展契约**的、能在生产中长期维护的 Agent 基建；
- 不绑定格物平台的数据库、MyBatis、国密、ULID 或任何业务包，使其可作为通用底座被复用。

框架自身的运行时依赖极简，只声明了四个核心依赖：

| 依赖 | 作用 |
|------|------|
| `spring-boot-starter` + `spring-boot-autoconfigure` | 自动装配、生命周期、日志 |
| `io.projectreactor:reactor-core` | `Flux<AgentEvent>` 流式执行核心 |
| `com.fasterxml.jackson.core:jackson-databind` | LLM 请求/响应、JSON Schema 序列化 |
| `org.projectlombok:lombok`（provided） | 模型类样板代码消除 |

不依赖 MyBatis、国密库、ULID 生成器或任何业务模块——所有需要业务数据的地方都退化为 SPI，由引入方按需实现。

## 2. 核心设计理念

框架在演进过程中遵循以下五条原则，它们构成了所有接口切分的依据：

**原则一：引擎与业务以 SPI 解耦。** 框架定义了一套业务 SPI（`PersistenceService`、`PermissionService`、`AuditService`、`SessionContextService`、`SandboxExecutor`、`ApiKeyDecryptor` 等），并为每个 SPI 提供 NoOp 默认实现（位于 `spi.defaults` 子包）。使用方注册同名 Bean 即覆盖，无需一行框架代码改动。框架本身不查数据库、不发审计、不调权限——这些全是使用方的职责。

**原则二：LLM 供应商无关，OpenAI 兼容为基线。** `LlmClient` 接口只有 `getProvider` / `chat` / `chatStream` 三个方法，`OpenAiCompatibleClient` 一份实现覆盖所有遵循 OpenAI Chat Completions 协议的供应商（DeepSeek、智谱、豆包、通义千问、LongCat 等）。新接私有协议只需实现 `LlmClient`。

**原则三：ReAct 即默认，工具可并行。** `ReactAgentExecutor` 是开箱即用的 `AgentExecutor`：每轮 LLM 推理后若无工具调用即返回；若有多个工具调用，则通过 `CompletableFuture` + 线程池**并行执行**，结果回灌后进入下一轮，直到无工具或达到 `maxToolRounds` 上限。

**原则四：编排即图，图与 ReAct 统一。** 编排层不把工作流与 Agent 循环割裂，而是统一建模为 `OrchestrationGraph`：`AGENT` 节点内部跑 ReAct 循环，`TOOL`/`HUMAN`/`ROUTER`/`PARALLEL`/`MERGE`/`SUBGRAPH` 节点与之同级，由 `Orchestrator` 统一调度。

**原则五：安全内建、默认拒绝。** 工具执行走安全管线（`SchemaValidator` → `PermissionService` → `SsrfValidator` → `CodeScanner` → 执行 → 截断 → `AuditService`）；SSRF 默认拒绝所有外部主机除非白名单放行；沙箱代码执行前先过 `DefaultCodeScanner` 扫描危险操作。

## 3. 能力矩阵

| 能力域 | 关键类型 | 说明 |
|--------|----------|------|
| 核心引擎 | `AgentEngine` / `AgentExecutor` / `ReactAgentExecutor` / `AgentTask` / `AgentEvent` / `AgentEngineConfig` | 门面 + 执行器接口 + ReAct 默认实现 |
| LLM 层 | `LlmClient` / `LlmClientRegistry` / `LlmProvider` / `OpenAiCompatibleClient` / `LlmRequestBodyBuilder` | 多供应商路由、同步/流式、reasoning_content 分离 |
| LLM 模型 | `LlmRequest` / `LlmResponse` / `LlmChunk` / `Message` / `ToolDefinition` / `ToolCall` | 请求/响应/分块/工具调用契约 |
| 工具层 | `Tool` / `@ToolProvider` / `ToolRegistry` / `ToolExecutor` / `ToolContext` / `ToolResult` / `ToolConfigSource` | 代码工具 + 配置工具 + 三通道执行 |
| 工具安全 | `SecurityCheck` / `SecurityChain` / `SchemaValidator` / `SsrfValidator` / `CodeScanner` / `DefaultCodeScanner` | 可插拔安全链 |
| 消息层 | `MessageBuilder` / `DefaultMessageBuilder` / `SystemPromptComposer` / `PromptDirective` | Prompt 组装与模式指令 |
| MCP 层 | `McpClient` / `StdioMcpClient` / `SseMcpClient` / `McpServerManager` / `McpServerConfigSource` / `McpServerDescriptor` / `McpToolDefinition` / `McpToolResult` | Model Context Protocol 工具接入 |
| 编排层 | `OrchestrationEngine` / `Orchestrator` / `GoalPlanner` / `DefaultGoalPlanner` / `AutonomousExecutor` | 图调度 + 目标分解 + 自主循环 |
| 编排模型 | `OrchestrationGraph` / `GraphNode` / `GraphEdge` / `NodeType` / `GraphType` / `OrchestrationMode` / `ExecutionMode` / `OrchestrationContext` / `OrchestrationResult` / `AutonomousGoal` | 编排图与执行上下文 |
| 编排模式 | `ModeHandler` / `PipelineModeHandler` / `SupervisorModeHandler` / `SwarmModeHandler` / `DebateModeHandler` | 4 种多 Agent 协作模式 |
| 编排运行时 | `AgentRuntime` / `ReactRuntime` / `PlanExecuteRuntime` / `ReflexionRuntime` | 3 种推理循环策略 |
| 编排角色 | `AgentRoleSpec` / `CapabilityCard` / `RoleRegistry` / `RoleConfigSource` | 12 个内置 SDLC 角色 |
| HITL | `HitlGateway` / `ApprovalRequest` / `HumanDecision` / `NoOpHitlGateway` | 人机协同审批 |
| 记忆 | `MemoryStore` / `MemoryRouter` / `MemoryFragment` / NoOp 实现 | 记忆存取与路由 |
| 认知 | `ReasoningKernel` / `EvolutionHook` / `ReflectionEngine` / `ReasoningResult` / NoOp 实现 | 推理内核/进化/反思 |
| SPI 聚合 | `PersistenceService` / `PermissionService` / `AuditService` / `SessionContextService` / `SandboxExecutor` / `ApiKeyDecryptor` + 模型 + defaults 子包 | 业务扩展契约 |
| 配置 | `AgentEngineAutoConfiguration` / `AgentEngineProperties`（前缀 `agent.engine`） | Spring Boot 自动装配 |
| 异常 | `AgentEngineException`（含 `code`） | 统一可预期错误 |

## 4. 与 LangGraph / AutoGen / CrewAI 的定位对比

| 维度 | 格物 Agent 引擎 | LangGraph | AutoGen | CrewAI |
|------|-----------------|-----------|---------|--------|
| 语言生态 | Java / Spring Boot | Python（异步框架无关） | Python | Python |
| 抽象主线 | ReAct 执行器 + 编排图 | 状态图（StateGraph）+ 检查点 | 对话式多 Agent | 角色+任务+流程 |
| 流式协议 | `Flux<AgentEvent>` 强类型事件 | 节点返回值/状态更新 | Async 消息 | 同步为主 |
| 工具安全 | 内建 SchemaValidator/SsrfValidator/CodeScanner 安全管线 | 无内建安全 | 无内建安全 | 无内建安全 |
| SPI 扩展 | 12+ 业务 SPI，全部 NoOp 可替换 | 回调/适配器 | 回调 | 配置驱动 |
| 供应商无关 | OpenAiCompatibleClient + LlmProvider SPI + 静态/动态双注册 | 自行封装 | 自行封装 | 自行封装 |
| 编排模式 | SUPERVISOR/PIPELINE/SWARM/DEBATE 4 模式 + 3 运行时 | 自由图 | 群聊/嵌套 | 顺序/层级 |
| 内置角色 | 12 个 SDLC 角色（需求/架构/开发/测试/运维...） | 无 | 无 | 无 |
| 状态持久化 | `PersistenceService` SPI（由使用方实现） | 内建 Checkpointer | 外接 | 外接 |
| 适用定位 | Java 生产环境的企业级 Agent 基座 | 灵活图编排研究型框架 | 多 Agent 对话研究 | 轻量多 Agent 编排 |

定位要点：格物 Agent 引擎不追求比对手"更灵活"，而追求在 **Java/Spring 生产环境**下做到"开箱即用、安全可控、业务无侵入"。它把安全管线、SPI 契约、供应商路由这些**生产基建**作为一等公民，而不是交给使用方自行拼装。

## 5. 适用场景

1. **企业内部 AI 助手 / 智能工单 / Copilot 后端**——需要调用工具、对接多 LLM、要求审计与权限控制，典型 ReAct 流程。
2. **软件研发全流程自动化（SDLC Agent）**——内置 12 个 SDLC 角色 + Pipeline/Supervisor 编排，直接落地"需求→架构→开发→审查→测试→部署"流水线。
3. **多 Agent 协作系统**——辩论（DEBATE）、群体交接（SWARM）、监督分派（SUPERVISOR）等需要多角色协同的场景。
4. **自主目标驱动执行**——通过 `AutonomousExecutor` + `GoalPlanner` 实现"给目标、自动分解、自动执行、自动反思重试"。
5. **需要安全隔离的代码执行平台**——`SandboxExecutor` SPI + `DefaultCodeScanner` + `CodeScanner` SPI 构成可定制的代码执行沙箱。
6. **MCP 工具生态接入**——通过 `StdioMcpClient` / `SseMcpClient` 接入外部 MCP Server，复用其工具能力。

不适用场景：纯 Python 技术栈、仅需单轮 LLM 问答无工具编排、不关心安全与审计的实验性项目——此时直接用供应商 SDK 更轻。

---

下一篇：[02-architecture.md](./02-architecture.md)——分层架构与数据流详解。