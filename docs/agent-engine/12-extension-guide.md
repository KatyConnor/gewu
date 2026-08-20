# 12 · 扩展实现指南

> 格物 Agent 引擎所有能力都以接口 + SPI 暴露，方便使用方在不动框架源码的前提下定制行为。本篇是常见扩展点的实现指南，每个都给出完整代码骨架与注册方式，供深度二次开发参考。

## 12.1 扩展点速览

| 扩展场景 | 接口 / 注解 | 包 | 注册方式 |
|----------|------------|----|----------|
| 自定义 LlmClient（非 OpenAI 协议） | `LlmClient` | `llm` | 注册 Spring Bean |
| 自定义工具 | `Tool` + `@ToolProvider` | `tool` | `@ToolProvider` 自动扫描 |
| 工具配置从 DB 加载 | `ToolConfigSource` | `tool` | `@Component` 覆盖 NoOp |
| 自定义 Agent 运行时 | `AgentRuntime` | `orchestration.runtime` | 注入编排节点使用 |
| 自定义编排模式 | `ModeHandler` | `orchestration.mode` | `Orchestrator.register` |
| 记忆对接向量库 | `MemoryStore` | `memory` | `@Component` 覆盖 NoOp |
| 内容审核安全检查 | `SecurityCheck` | `tool.security` | 注册 Spring Bean |
| 角色从 DB 加载 | `RoleConfigSource` | `orchestration.role` | `@Component` 覆盖 NoOp |
| 配置覆盖 | `AgentEngineProperties` | `config` | `application.yml` |

## 12.2 自定义 LlmClient（对接非 OpenAI 协议）

当供应商不遵循 OpenAI Chat Completions 协议时，实现 `LlmClient` 接口并注册为 Bean：

```java
package com.gewu.app.agent.llm;

import com.gewu.agent.engine.llm.LlmClient;
import com.gewu.agent.engine.llm.model.*;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class AnthropicClient implements LlmClient {

    private final HttpClient http;          // 使用方 HTTP 客户端

    public AnthropicClient(HttpClient http) { this.http = http; }

    @Override public String getProvider() { return "anthropic"; }

    @Override
    public LlmResponse chat(LlmRequest request) {
        // 1. 把 LlmRequest 转换为 Anthropic Messages API 请求体
        String body = toAnthropicBody(request);
        // 2. 发请求（含 anthropic-version / x-api-key 头）
        HttpResponse<String> resp = http.post("/v1/messages", body, headers());
        // 3. 解析响应 -> LlmResponse(content, reasoningContent, toolCalls, tokens...)
        return parseAnthropic(resp.body());
    }

    @Override
    public Flux<LlmChunk> chatStream(LlmRequest request) {
        return http.postStream("/v1/messages", toAnthropicBody(request), headers())
                   .map(this::toLlmChunk);    // 把 Anthropic 流式块 -> LlmChunk
    }
    // ... toAnthropicBody / parseAnthropic / toLlmChunk 省略
}
```

`LlmClient` 方法：`getProvider()`（供应商标识）/ `chat(LlmRequest)`（同步）/ `chatStream(LlmRequest)`（流式返回 `Flux<LlmChunk>`）。注册后 `LlmClientRegistry` 按 `provider` 索引，`AgentTask.modelProvider="anthropic"` 命中此实现。`LlmChunk`/`LlmRequest`/`LlmResponse` 位于 `llm.model` 包。

## 12.3 自定义工具（Tool 接口 + @ToolProvider）

```java
package com.gewu.app.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.llm.model.ToolDefinition;
import com.gewu.agent.engine.tool.*;
import org.springframework.stereotype.Component;

@ToolProvider(category = "CODE")     // 等价 @Component，自动注册到 ToolRegistry
public class RefactorTool implements Tool {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RefactorService svc;

    public RefactorTool(RefactorService svc) { this.svc = svc; }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("refactor_code")
                .description("重构指定源码文件：提取方法/消除坏味道")
                .parameters("""
                    {"type":"object","properties":{
                      "filePath":{"type":"string"},
                      "smell":{"type":"string","enum":["long_method","large_class","duplicate"]}
                    },"required":["filePath","smell"]}""")
                .build();
    }

    @Override
    public ToolResult invoke(String arguments, ToolContext context) {
        try {
            JsonNode a = mapper.readTree(arguments);
            String out = svc.refactor(a.path("filePath").asText(), a.path("smell").asText(),
                    context.getProjectId());
            return ToolResult.success(out, 0);
        } catch (Exception e) {
            return ToolResult.failure("重构失败: " + e.getMessage(), 0);
        }
    }
}
```

要点：`ToolProvider.category()` 取值 `GIT/CI_CD/DB/DOC/SEARCH/CODE/OPS/CUSTOM`。`ToolContext` 提供 `userId`/`sessionId`/`agentId`/`timeout`/`sandboxEnabled`/`sandboxImage`/`projectId`。执行必经 `ToolExecutor` 安全管线，详见 [05 工具层](05-tool-layer.md)。

## 12.4 自定义 ToolConfigSource（从 DB 加载工具）

DB 配置的 `http`/`mcp`/`code_execute` 类型工具走 `ToolConfigSource`，与代码工具互补：

```java
package com.gewu.app.agent.spi;

import com.gewu.agent.engine.spi.ToolConfig;
import com.gewu.agent.engine.tool.ToolConfigSource;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class DbToolConfigSource implements ToolConfigSource {

    private final ToolMapper mapper;

    public DbToolConfigSource(ToolMapper mapper) { this.mapper = mapper; }

    @Override
    public List<ToolConfig> loadTools() {
        return mapper.selectAllEnabled().stream()
                .map(t -> ToolConfig.builder()
                        .toolName(t.getToolName()).description(t.getDescription())
                        .requestSchema(t.getRequestSchema()).toolType(t.getToolType())
                        .endpoint(t.getEndpoint()).mcpServerId(t.getMcpServerId())
                        .timeoutMs(t.getTimeoutMs()).sortOrder(t.getSortOrder())
                        .status(t.getStatus()).build())
                .toList();
    }

    @Override
    public List<ToolConfig> loadToolsByAgent(String agentId) {
        // 按 Agent 关联表查询（更高效，避免全量过滤）
        return mapper.selectEnabledByAgent(agentId).stream()
                .map(t -> ToolConfig.builder()
                        .toolName(t.getToolName()).toolType(t.getToolType())
                        .endpoint(t.getEndpoint()).mcpServerId(t.getMcpServerId())
                        .requestSchema(t.getRequestSchema()).status(t.getStatus()).build())
                .toList();
    }
}
```

注册即覆盖 `NoOpToolConfigSource`。`ToolConfig.toolType`：`http`（HTTP 端点调用）/ `mcp`（MCP 服务器，配 `mcpServerId`）/ `code_execute`（沙箱执行，见 12.5）。

## 12.5 自定义 AgentRuntime（新执行策略）

`AgentRuntime` 决定 AGENT 节点如何推理。内置 ReAct/PlanExecute/Reflexion 三种，自定义示例如下（Tree-of-Thought 探索）：

```java
package com.gewu.app.agent.runtime;

import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.orchestration.runtime.AgentRuntime;
import reactor.core.publisher.Flux;

public class TotRuntime implements AgentRuntime {

    private final AgentExecutor executor;

    public TotRuntime(AgentExecutor executor) { this.executor = executor; }

    @Override
    public Flux<AgentEvent> execute(AgentTask task) {
        return Flux.create(sink -> {
            sink.next(AgentEvent.builder().type(AgentEvent.STATUS)
                    .content("Tree-of-Thought 探索中...").build());
            // 1. 分支：克隆任务并行多路径 ReAct
            // 2. 汇总：选最优分支产出
            //   简化实现：委托底层执行器完成真实推理
            executor.executeStream(task).subscribe(
                    sink::next, sink::error, sink::complete);
        });
    }
}
```

在编排图中通过 `GraphNode.executionMode` 或 `AgentRoleSpec.defaultExecutionMode` 指定使用此运行时（使用方在组装编排图节点时按 `executionMode` 选择 `AgentRuntime` 实例执行）。

## 12.6 自定义 ModeHandler（新编排模式）

```java
package com.gewu.app.agent.mode;

import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.orchestration.mode.ModeHandler;
import com.gewu.agent.engine.orchestration.model.*;
import reactor.core.publisher.Flux;
import java.util.Map;

public class HierarchicalModeHandler implements ModeHandler {

    private final AgentExecutor executor;

    public HierarchicalModeHandler(AgentExecutor executor) { this.executor = executor; }

    @Override public String mode() { return "HIERARCHICAL"; }

    @Override
    public Flux<AgentEvent> run(OrchestrationGraph graph, OrchestrationContext ctx) {
        return Flux.create(sink -> {
            sink.next(AgentEvent.builder().type("graph_start")
                    .metadata(Map.of("mode", "HIERARCHICAL", "executionId", ctx.getExecutionId())).build());
            // 按树形边递归执行管理者->子节点...
            // 完成时：
            sink.next(AgentEvent.builder().type("graph_complete")
                    .metadata(Map.of("status", "SUCCESS", "output", "...")).build());
            sink.complete();
        });
    }
}
```

注册到 `Orchestrator`（覆盖默认或新增模式）：

```java
@Configuration
public class CustomModeConfig {
    @Bean
    public Orchestrator orchestrator(AgentExecutor executor) {
        Orchestrator o = new Orchestrator(executor);   // 含 4 个内置模式
        o.register(new HierarchicalModeHandler(executor));   // 追加自定义模式
        return o;   // 该 Bean 覆盖自动装配的 Orchestrator
    }
}
```

`graph.mode` 取 `HIERARCHICAL` 时即命中此处理器。编排事件统一为 `AgentEvent`，扩展事件见 [13 事件协议](13-event-protocol.md)。

## 12.7 自定义 MemoryStore（对接 pgvector / Milvus）

```java
package com.gewu.app.agent.memory;

import com.gewu.agent.engine.memory.MemoryFragment;
import com.gewu.agent.engine.memory.MemoryStore;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class MilvusMemoryStore implements MemoryStore {

    private final MilvusClient milvus;
    private final EmbeddingService embed;

    public MilvusMemoryStore(MilvusClient milvus, EmbeddingService embed) {
        this.milvus = milvus; this.embed = embed;
    }

    @Override
    public void store(MemoryFragment f) {
        if (f.getVector() == null) f.setVector(embed.embed(f.getContent()));
        milvus.insert("memory_" + f.getDomain(), f);   // 按 domain 分集合
    }

    @Override
    public List<MemoryFragment> retrieve(String domain, String query, int topK) {
        float[] qv = embed.embed(query);
        return milvus.search("memory_" + domain, qv, topK);  // 余弦相似度
    }

    @Override
    public List<MemoryFragment> retrieveByMetadata(String domain, Map<String, Object> filter) {
        return milvus.query("memory_" + domain, filter);
    }

    @Override
    public void clear(String domain) {
        milvus.drop("memory_" + domain);
    }
}
```

要点：`MemoryFragment.vector` 由使用方在 `store` 时生成（框架不内置 embedding 模型）；按 `domain` 分集合/分表隔离不同角色记忆。`MemoryRouter` 可一并实现以定制注入策略，详见 [09 记忆与认知](09-memory-cognition.md)。

## 12.8 自定义 SecurityCheck（内容审核）

`SecurityCheck` 是工具执行前可插拔的安全校验，多个组成 `SecurityChain` 按序执行。注册自定义检查即可加入链：

```java
package com.gewu.app.agent.security;

import com.gewu.agent.engine.AgentEngineException;
import com.gewu.agent.engine.spi.ToolConfig;
import com.gewu.agent.engine.tool.ToolContext;
import com.gewu.agent.engine.tool.security.SecurityCheck;
import org.springframework.stereotype.Component;

@Component
public class ContentModerationCheck implements SecurityCheck {

    private final ModerationClient moderation;

    public ContentModerationCheck(ModerationClient m) { this.moderation = m; }

    @Override
    public void check(String toolName, String arguments, ToolContext context, ToolConfig config) {
        // 1. 对 arguments 做敏感信息脱敏 / 敏感词检测
        if (containsSensitiveData(arguments)) {
            throw AgentEngineException.of("TOOL_CONFIG_INVALID",
                    "工具参数含敏感信息，已被内容审核拦截");
        }
        // 2. 对高风险工具（如 code_execute）调用外部审核服务
        if (config != null && "code_execute".equals(config.getToolType())) {
            if (!moderation.approve(arguments, context.getUserId())) {
                throw AgentEngineException.of("SANDBOX_DANGEROUS_OPERATION",
                        "代码内容未通过安全审核");
            }
        }
        // 通过则静默返回，链中下一个 check 继续执行
    }

    private boolean containsSensitiveData(String args) { /* ... */ return false; }
}
```

`AgentEngineAutoConfiguration` 装配 `SecurityChain` 时会收集所有 `SecurityCheck` Bean（含 `SchemaValidator`）按序执行。框架默认含 `SchemaValidator`，使用方可加 `SsrfValidator`、`CodeScanner`（已装配）以及自定义审核。错误码参见 [10 SPI 规范](10-spi-reference.md#1015-统一异常与错误码)。

## 12.9 配置覆盖说明

`AgentEngineProperties` 全部子项均可在 `application.yml` 覆盖，常用项：

| 属性路径 | 默认 | 作用 |
|----------|------|------|
| `agent.engine.engine.max-tool-rounds` | 10 | 单次执行最大工具轮次 |
| `agent.engine.engine.default-max-tokens` | 8192 | 默认 max_tokens |
| `agent.engine.engine.default-temperature` | 0.7 | 默认采样温度 |
| `agent.engine.engine.tool-executor-core-pool-size` | 8 | 工具并行线程池核心 |
| `agent.engine.llm.connect-timeout` | 30s | LLM HTTP 连接超时 |
| `agent.engine.llm.request-timeout` | 120s | 同步请求超时 |
| `agent.engine.tool.allowed-hosts` | (空) | HTTP 工具主机白名单（空=拒绝全部外部地址） |
| `agent.engine.tool.max-redirects` | 5 | HTTP 工具最大重定向 |
| `agent.engine.tool.max-output-size` | 10240 | 工具输出最大字节数 |
| `agent.engine.tool.default-timeout-seconds` | 30 | 默认工具超时 |

需运行时覆盖 Bean 时，提供同名 `@Bean` 即触发 `@ConditionalOnMissingBean` 让默认实现让位（如 `llmHttpClient`、`agentToolExecutor`、`Orchestrator` 等）。

## 12.10 扩展最佳实践

1. **优先 SPI 再子类** —— 能通过实现 SPI 达成的定制不要继承框架内部类，避免被框架内部演进破坏。
2. **NoOp 先行** —— 先用默认实现跑通主链路，再逐个 SPI 替换为真实实现，降低排障面。
3. **错误码统一** —— SPI 实现中可预期错误抛 `AgentEngineException`，便于上层统一捕获转换。
4. **Bean 优先级** —— 自定义 Bean 自动覆盖 NoOp（`@ConditionalOnMissingBean`）；多实现时用 `@Order` 或 `@Primary` 控制解析。
5. **流式优先** —— 自定义 ModeHandler/Runtime 产出 `Flux<AgentEvent>`，复用框架 SSE 协议，不破坏观察性。

## 12.11 小结

引擎的扩展面通过接口与 SPI 完整开放：从 LLM 客户端、工具、工具配置源，到运行时、编排模式、记忆、安全检查、角色配置源、配置覆盖，每一层都能在不改框架源码的前提下定制。配合 NoOp 默认与 `@ConditionalOnMissingBean`，使用方既能零成本起步，也能按业务深度逐层替换，形成"框架沉淀通用能力、业务接管定制能力"的清晰边界。