# 格物 Agent 引擎 (Gewu Agent Engine)

> 一套可独立引入的 Agent 智能体引擎框架 —— 任何 Spring Boot 项目引入依赖后，按 SPI 规范实现业务扩展点，即可获得完整的 Agent 智能体系统能力。

[![Java 21](https://img.shields.io/badge/Java-21-orange)]() [![Spring Boot 3.2](https://img.shields.io/badge/Spring%20Boot-3.2-green)]() [![Reactor](https://img.shields.io/badge/Reactor-Flux-blue)]()

## 为什么需要它

构建一套 Agent 智能体系统通常要反复解决相同的问题：如何对接多个 LLM 供应商、如何驱动 ReAct 推理循环、如何让 Agent 安全地调用工具、如何把推理过程实时流式推送给前端、如何让多个 Agent 协作、如何在关键节点让人工介入……这些问题与具体业务无关，应当沉淀为一套可复用的引擎。

**格物 Agent 引擎** 正是为此而生。它从格物平台（一个 DDD 架构的 AI 开发协作平台）的 Agent 体系中抽离而来，剥离全部业务依赖，沉淀为独立 Maven 模块，对标行业内的 LangGraph / AutoGen，但面向 Java + Spring Boot 生态。

## 能力矩阵

| 能力 | 说明 | 模块 |
|------|------|------|
| **LLM 抽象层** | 统一 `LlmClient` 接口，OpenAI 兼容客户端开箱即用，支持多供应商动态路由 | `llm` |
| **ReAct 执行引擎** | Thought→Action→Observation 循环 + 工具并行调用，同步与流式双模式 | `core` |
| **工具体系** | `@ToolProvider` 代码注册 + DB 配置双轨，HTTP / MCP / 沙箱三通道执行 | `tool` |
| **安全管线** | 可插拔安全链：JSON Schema 校验 → 权限评估 → SSRF 防护 → 代码扫描 → 审计 | `tool.security` |
| **MCP 集成** | Stdio / SSE 两种传输，MCP 服务器连接管理与工具调用 | `mcp` |
| **编排引擎** | "编排即图"，统一工作流 DAG 与 Agent ReAct，四种多 Agent 模式 | `orchestration` |
| **多 Agent 模式** | Supervisor 路由 / Pipeline 流水线 / Swarm 交接 / Debate 辩论 | `orchestration.mode` |
| **多种运行时** | ReAct / Plan-Execute / Reflexion 三种执行策略 | `orchestration.runtime` |
| **自主目标驱动** | 给定高层目标，自动分解 + 执行 + 验收 + 反思重做，含防失控边界 | `orchestration` |
| **人机协同 (HITL)** | 审批 / 接管 / 回退 / 异步通知，通过 SPI 对接审批渠道 | `hitl` |
| **记忆 SPI** | 语义 / 情景 / 程序性 / 参数化四类记忆，不绑定特定向量库 | `memory` |
| **认知/进化 SPI** | Planner / Solver / Critic 推理内核 + EvolutionHook 进化闭环 | `cognition` |
| **流式协议** | 统一 `AgentEvent`，覆盖思考 / 内容 / 工具调用 / 编排事件全链路 | `core.event` |
| **SPI 扩展** | 11 个业务适配 SPI，全部 NoOp 默认实现，**零业务实现即可运行** | `spi` |

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>com.gewu</groupId>
    <artifactId>gewu-agent-engine</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. 配置 LLM 供应商

```yaml
agent:
  engine:
    engine:
      max-tool-rounds: 10
      default-max-tokens: 8192
    llm:
      connect-timeout: 30s
```

注册一个 LLM 客户端 Bean：

```java
@Bean
public LlmClient myLlmClient(LlmRequestBodyBuilder builder, ObjectMapper mapper, HttpClient http) {
    return new OpenAiCompatibleClient(
        "my-provider",            // provider 编码
        "sk-xxx",                  // API Key
        "https://api.example.com/v1/chat/completions",
        mapper, http, builder);
}
```

### 3. 执行 Agent 任务

```java
@Autowired
private AgentEngine agentEngine;

// 同步执行
LlmResponse response = agentEngine.execute(AgentTask.builder()
    .agentId(null)                  // 直接对话，agentId 可空
    .modelProvider("my-provider")   // 显式指定供应商
    .modelName("gpt-4o")
    .message("帮我用 Java 写一个快速排序")
    .agentMode("expert")
    .build());

// 流式执行（SSE）
Flux<AgentEvent> stream = agentEngine.executeStream(AgentTask.builder()
    .modelProvider("my-provider")
    .modelName("deepseek-chat")
    .message("分析这段代码的性能瓶颈")
    .build());
```

### 4. 实现业务 SPI（按需）

零业务实现也能运行（全部 NoOp）。生产环境按需实现：

```java
@Component
public class DbPersistenceService implements PersistenceService {
    // 桥接你的 Agent / Tool / Execution 数据表
}
```

## 文档导航

| 文档 | 内容 |
|------|------|
| [01-overview.md](01-overview.md) | 定位、设计理念、能力矩阵 |
| [02-architecture.md](02-architecture.md) | 分层架构、模块依赖、数据流 |
| [03-core-engine.md](03-core-engine.md) | 执行引擎、ReAct 循环、流式协议 |
| [04-llm-layer.md](04-llm-layer.md) | LLM 抽象、多供应商、注册机制 |
| [05-tool-layer.md](05-tool-layer.md) | 工具体系、安全管线、@ToolProvider |
| [06-mcp.md](06-mcp.md) | MCP 集成 |
| [07-orchestration.md](07-orchestration.md) | 编排引擎、四种模式、三种运行时、自主目标 |
| [08-hitl.md](08-hitl.md) | 人机协同 |
| [09-memory-cognition.md](09-memory-cognition.md) | 记忆与认知 SPI |
| [10-spi-reference.md](10-spi-reference.md) | SPI 接口规范（签名 / 契约 / 实现要求） |
| [11-quick-start.md](11-quick-start.md) | 引入依赖 → 实现 SPI → 运行完整示例 |
| [12-extension-guide.md](12-extension-guide.md) | 扩展实现指南（自定义 LLM / Tool / Runtime / Mode / Memory） |
| [13-event-protocol.md](13-event-protocol.md) | 流式事件协议完整定义 |

## 设计原则

1. **零业务依赖** — 框架仅依赖 Spring Boot + Reactor + Jackson + Lombok，不绑定 MyBatis / 国密 / ULID / 任何业务包
2. **抽象优先 + SPI 扩展** — 框架定义接口与通用实现，业务能力通过 SPI 接口由使用方实现；全部 SPI 提供 NoOp 默认实现，**开箱即用**
3. **流式优先** — 核心基于 Reactor `Flux<AgentEvent>`，适配 SSE 实时推送
4. **双轨注册** — 工具与模型均支持代码注册（`@ToolProvider`）+ SPI 配置源（DB / 文件）
5. **编排即图** — 统一工作流 DAG 与 Agent ReAct 为编排图模型，消除双轨

## 技术栈

- Java 21 + Spring Boot 3.2
- Project Reactor（流式核心）
- Jackson（JSON 处理）
- Lombok（样板代码消除）

## 许可

内部使用，随主项目分发。