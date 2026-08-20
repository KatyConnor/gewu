# 格物 Agent 引擎抽离方案

## 一、目标
将 gewu-platform 中分散在 `gewu-application/agent`、`gewu-infrastructure/llm`、`gewu-infrastructure/mcp` 的 agent 架构体系，抽离为**独立 Maven 子模块 `gewu-agent-engine`**（artifactId `com.gewu:gewu-agent-engine`）。该模块达到独立框架效果：任何 Spring Boot 项目引入依赖后，按 SPI 规范实现业务扩展点，即可获得一套完整的 Agent 智能体系统能力，对标 LangGraph / AutoGen。同步输出详细技术文档。

## 二、设计原则
1. **零业务依赖**：框架仅依赖 `spring-boot-starter` + `reactor-core` + `jackson-databind` + `lombok` + `slf4j`。**不依赖** MyBatis-Plus / 国密 / ULID / wenshi / pgvector / gewu-common 业务包。
2. **抽象优先 + SPI 扩展**：框架定义接口与通用实现，业务能力通过 SPI 接口由使用方实现；所有 SPI 提供 `NoOp` 默认实现，**开箱即用**。
3. **流式优先**：核心基于 Reactor `Flux<AgentEvent>`。
4. **双轨工具/模型注册**：代码注册（`@ToolProvider`/`@LlmProvider`）+ SPI 配置源（DB/文件）。
5. **编排即图**：落地文档 29 蓝图，统一工作流 DAG 与 Agent ReAct 为编排图模型。

## 三、模块结构
新增顶层模块 `gewu-agent-engine/`，加入根 `pom.xml` `<modules>`。内部包结构：

```
com.gewu.agent.engine
├── core/                 核心执行引擎
│   ├── AgentEngine                    门面入口(execute/executeStream)
│   ├── AgentExecutor                  执行器接口
│   ├── ReactAgentExecutor             ReAct+工具并行(源自 AgentExecutionEngine)
│   ├── AgentTask / AgentResult        任务与结果模型
│   ├── AgentEngineConfig              maxRounds/maxTokens/线程池
│   └── event/AgentEvent               统一流式事件(扩展 AgentChunk 至编排层)
├── llm/                  LLM 抽象层
│   ├── LlmClient                      接口(getProvider/chat/chatStream)
│   ├── LlmClientRegistry              注册中心(替代 Factory,代码注册+SPI,不绑DB)
│   ├── LlmProvider                    SPI(动态供应商配置源)
│   ├── model/                         LlmRequest/Response/Chunk/Message/ToolDefinition/ToolCall/Usage
│   ├── OpenAiCompatibleClient         通用实现(直接迁移,纯构造注入)
│   ├── LlmRequestBodyBuilder          请求体构建
│   └── LlmAutoConfiguration           自动注册 HttpClient/ObjectMapper
├── tool/                 工具抽象层
│   ├── Tool                           接口(getDefinition/invoke)
│   ├── ToolRegistry                   注册中心(@ToolProvider 扫描 + ToolConfigSource)
│   ├── ToolExecutor                   执行器(安全管线编排)
│   ├── ToolContext / ToolResult
│   ├── @ToolProvider                  代码级注册注解
│   ├── ToolConfigSource               SPI(DB/文件配置源)
│   └── security/                      可插拔安全链
│       ├── SecurityCheck / SecurityChain
│       ├── SchemaValidator            默认(JSON Schema 校验)
│       ├── SsrfValidator              默认
│       └── CodeScanner                SPI(沙箱代码扫描)
├── message/              消息构建
│   ├── MessageBuilder                 SPI(构建 LLM 消息)
│   ├── SystemPromptComposer           系统提示词组装
│   ├── PromptDirective                agentMode/thinkingStyle 指令
│   └── HistoryResolver                历史上下文 SPI
├── mcp/                  MCP 协议层
│   ├── McpClient / StdioMcpClient / SseMcpClient
│   ├── McpServerManager
│   └── McpToolDefinition / McpToolResult
├── orchestration/        编排引擎(文档29蓝图)
│   ├── model/                         OrchestrationGraph/GraphNode/GraphEdge/NodeType/Context/Result
│   ├── OrchestrationEngine            接口(execute/executeStream/executeGoal/pause/resume/cancel)
│   ├── Orchestrator                   核心调度器(拓扑排序+节点分派)
│   ├── GoalPlanner                    目标分解 SPI
│   ├── AutonomousExecutor             自主目标循环(含防失控边界)
│   ├── mode/                          ModeHandler 接口 + Supervisor/Pipeline/Swarm/Debate 四实现
│   ├── runtime/                       AgentRuntime 接口 + React/PlanExecute/Reflexion 三实现
│   └── role/                          AgentRoleSpec/CapabilityCard/RoleRegistry
├── hitl/                 人机协同
│   ├── HitlGateway                    接口(requestApproval/submitDecision/takeover/rollback)
│   ├── ApprovalRequest / HumanDecision
│   └── NoOpHitlGateway                默认空实现
├── memory/               记忆 SPI(不绑 pgvector)
│   ├── MemoryStore / MemoryRouter / MemoryInjector
│   └── NoOpMemoryStore                默认空实现
├── cognition/            认知/进化 SPI(不绑 wenshi)
│   ├── ReasoningKernel                SPI(Planner/Solver/Critic)
│   ├── EvolutionHook / ReflectionEngine
│   └── NoOpEvolutionHook              默认空实现
├── spi/                  业务适配 SPI 聚合
│   ├── PersistenceService             Agent配置/执行记录/工具配置 读写
│   ├── PermissionService              工具权限评估
│   ├── AuditService                   审计日志
│   ├── SessionContextService          会话历史
│   ├── SandboxExecutor                代码沙箱执行
│   ├── ApiKeyDecryptor                API Key 解密
│   └── NoOpSpi                        全部 NoOp 默认(开箱即用)
└── config/               Spring Boot 自动配置
    ├── AgentEngineAutoConfiguration   装配引擎/注册中心/默认实现
    └── AgentEngineProperties          配置属性(prefix=agent.engine)
```

## 四、现有代码 → 框架映射
| 现有实现 | 框架归属 | 处理 |
|---|---|---|
| `AgentExecutionEngine`(ReAct循环/流式/工具并行) | `core.ReactAgentExecutor` | 逻辑迁移，解耦 Mapper |
| `LlmClient`/`LlmClientFactory` | `llm.LlmClient`/`LlmClientRegistry` | 抽象迁移，Factory 改 Registry 去 DB 依赖 |
| `OpenAiCompatibleClient`/`LlmRequestBodyBuilder` | `llm.*` | 直接迁移(纯构造注入) |
| LLM 模型类(Request/Response/Chunk/Message/ToolDef/ToolCall) | `llm.model.*` | 迁移 |
| `ToolExecutionService` | `tool.ToolExecutor` | 逻辑迁移，通道改 SPI |
| `ToolSchemaValidator`/`SsrfValidator` | `tool.security.*` | 迁移为默认实现 |
| `SandboxCodeScanner` | `tool.security.CodeScanner` | 改 SPI |
| `McpClient`/`McpServerManager`/Stdio/Sse | `mcp.*` | 迁移 |
| `AgentMessageBuilder` | `message.MessageBuilder`+`SystemPromptComposer` | 逻辑迁移，技能注入改 SPI |
| `AgentChunk`/`AgentExecutionRequest`/`ToolContext`/`ToolResult` | `core.event.AgentEvent`/`AgentTask`/`tool.*` | 迁移+扩展 |
| `PermissionEvaluationService` | `spi.PermissionService`(业务实现) | 业务侧实现 SPI |
| `AuditLogService` | `spi.AuditService`(业务实现) | 业务侧实现 |
| `SessionContextService` | `spi.SessionContextService`(业务实现) | 业务侧实现 |
| `SandboxClient` | `spi.SandboxExecutor`(业务实现) | 业务侧实现 |
| `AgentMapper`/`AgentToolMapper` 等 | `spi.PersistenceService`(业务实现) | 业务侧实现 |
| `ModelProviderMapper`+`ApiKeyCryptoService` | `llm.LlmProvider`+`spi.ApiKeyDecryptor`(业务实现) | 业务侧实现 |
| Wenshi reasoning/knowledge/learning | `cognition`/`memory` SPI(业务实现,可选) | 业务侧可选实现 |

## 五、SPI 设计要点
框架定义 11 个 SPI 接口，全部有 NoOp 默认实现，保证**零业务实现也能运行**：
- `PersistenceService`：AgentSpec(配置)/执行记录/工具配置的读写（NoOp→内存 Map）
- `PermissionService`：工具权限评估（NoOp→全允许）
- `AuditService`：审计记录（NoOp→仅日志）
- `SessionContextService`：会话历史消息（NoOp→空）
- `SandboxExecutor`：代码沙箱执行（NoOp→抛出不支持）
- `ToolConfigSource`：外部工具配置源（NoOp→空）
- `LlmProvider`：动态供应商配置（NoOp→空，仅代码注册可用）
- `ApiKeyDecryptor`：API Key 解密（NoOp→原样透传）
- `MemoryStore`/`ReasoningKernel`/`EvolutionHook`/`HitlGateway`：认知与协同 SPI（NoOp→空操作）

使用方按需实现并注册为 Spring Bean，框架自动装配。

## 六、实施阶段
**Phase 1 — 框架骨架与核心引擎**（最高优先）
- 建模块 `gewu-agent-engine`（pom + 加入根 modules）
- `llm` 全层（接口/Registry/模型/OpenAiCompatibleClient/RequestBodyBuilder/AutoConfig）
- `tool` 全层（Tool/Registry/Executor/Context/Result/@ToolProvider/security 链）
- `message` 层（MessageBuilder/SystemPromptComposer/PromptDirective/HistoryResolver）
- `mcp` 全层（Client/Stdio/Sse/Manager/模型）
- `core` 层（AgentEngine/ReactAgentExecutor/AgentTask/AgentResult/AgentEvent/Config）
- `spi` 全部接口 + NoOp 实现
- `config` 自动配置（AgentEngineAutoConfiguration/Properties）
- 验证：`mvn -pl gewu-agent-engine compile` 通过

**Phase 2 — 编排引擎落地**
- `orchestration.model` 编排图模型
- `OrchestrationEngine`/`Orchestrator`/`GoalPlanner`/`AutonomousExecutor`
- `mode` 四种 ModeHandler（Supervisor/Pipeline/Swarm/Debate）
- `runtime` 三种 AgentRuntime（React 封装 Phase1 / PlanExecute / Reflexion）
- `role` 角色体系（AgentRoleSpec/CapabilityCard/RoleRegistry）
- `hitl` 接口 + NoOp

**Phase 3 — 认知/记忆 SPI**
- `memory`（MemoryStore/Router/Injector + NoOp）
- `cognition`（ReasoningKernel/EvolutionHook/ReflectionEngine + NoOp）
- 编排层 `EvolutionHook` 嵌入点对接

**Phase 4 — 业务适配验证**
- `gewu-application` 新增 `agent/adapter` 包，实现框架全部业务 SPI（PersistenceService/PermissionService/AuditService/SessionContextService/SandboxExecutor/LlmProvider/ApiKeyDecryptor），桥接现有 Mapper/Service
- 改造 `AiChatController`/`AgentExecutionController` 走框架 `AgentEngine`
- 保留 Wenshi 路由作为 cognition SPI 可选实现
- 验证：`mvn -pl gewu-application,gewu-interface compile` 通过，SSE 流式不回归

**Phase 5 — 技术文档**（与各阶段同步产出）
`docs/agent-engine/` 下：
1. `README.md` — 框架总览与快速开始
2. `01-overview.md` — 定位/设计理念/能力矩阵
3. `02-architecture.md` — 分层架构/模块依赖/数据流
4. `03-core-engine.md` — 执行引擎/ReAct 循环/流式协议
5. `04-llm-layer.md` — LLM 抽象/多供应商/注册机制
6. `05-tool-layer.md` — 工具体系/安全管线/@ToolProvider
7. `06-mcp.md` — MCP 集成
8. `07-orchestration.md` — 编排引擎/四种模式/三种运行时/自主目标
9. `08-hitl.md` — 人机协同
10. `09-memory-cognition.md` — 记忆与认知 SPI
11. `10-spi-reference.md` — SPI 接口规范（每个接口签名/契约/实现要求）
12. `11-quick-start.md` — 引入依赖→实现 SPI→运行完整示例
13. `12-extension-guide.md` — 扩展实现指南（自定义 LLM/Tool/Runtime/Mode/Memory）
14. `13-event-protocol.md` — 流式事件协议完整定义

## 七、验证方式
- 每个 Phase 完成 `mvn compile` 验证可编译
- Phase 1 完成后，框架可独立引入（仅 SPI NoOp 即可跑通 ReAct + 工具调用）
- Phase 4 完成后，现有 AiChatController SSE 流式对话在框架上运行不回归
- 文档与代码同步，SPI 规范与接口签名一致

## 八、交付物
1. `gewu-agent-engine/` 独立框架模块（核心+编排+SPI+自动配置+NoOp，可独立编译发布）
2. `gewu-application/agent/adapter/` 业务 SPI 适配实现
3. `docs/agent-engine/` 14 篇技术文档
4. 现有功能迁移到框架上运行（不回归）

> 说明：工程量大，按 Phase 1→5 顺序推进，优先保证框架本身完整可用 + 文档，业务适配作为收尾验证。