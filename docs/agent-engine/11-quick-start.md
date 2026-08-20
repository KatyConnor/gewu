# 11 · 快速开始完整教程

> 本篇以一个可运行的最小路径串起格物 Agent 引擎的全部接入点：引入依赖 → 配置 → 注册 LLM 客户端 → 同步/流式对话 → 注册自定义工具 → 实现 PersistenceService 对接数据库 → 实现 LlmProvider 对接 model_provider 表 → 运行验证。照着做即可在 Spring Boot 项目中获得完整 Agent 能力。

## 11.1 引入依赖

```xml
<dependency>
    <groupId>com.gewu</groupId>
    <artifactId>gewu-agent-engine</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

引擎仅依赖 Spring Boot 3.2 + Reactor + Jackson + Lombok，不绑定 MyBatis / 国密 / 任何业务包。引入后 `AgentEngineAutoConfiguration` 自动装配（注册于 `META-INF/spring/AutoConfiguration.imports`），默认所有 SPI 走 NoOp 实现，**无需任何业务实现即可启动**。

## 11.2 application.yml 配置

```yaml
agent:
  engine:
    engine:
      max-tool-rounds: 10           # 单次执行最大工具调用轮次
      default-max-tokens: 8192
      default-temperature: 0.7
      tool-executor-core-pool-size: 8
      tool-executor-max-pool-size: 16
      tool-executor-queue-capacity: 100
    llm:
      connect-timeout: 30s
      request-timeout: 120s
    tool:
      allowed-hosts: ""             # HTTP 工具主机白名单（逗号分隔，空=拒绝全部外部地址）
      max-redirects: 5
      max-output-size: 10240        # 工具输出最大字节数
      default-timeout-seconds: 30
```

`AgentEngineProperties`（前缀 `agent.engine`）分三组：`engine`（执行引擎）、`llm`（HTTP 超时）、`tool`（工具安全/超时）。详见 [04 LLM 层](04-llm-layer.md) 与 [05 工具层](05-tool-layer.md)。

## 11.3 注册 LLM 客户端 Bean

零配置直连一个 OpenAI 兼容供应商，只需注册一个 `LlmClient` Bean（框架自动扫描注入 `LlmClientRegistry`）：

```java
package com.gewu.app.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.llm.LlmClient;
import com.gewu.agent.engine.llm.LlmRequestBodyBuilder;
import com.gewu.agent.engine.llm.OpenAiCompatibleClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

@Configuration
public class LlmConfig {

    @Bean
    public LlmClient deepseekClient(ObjectMapper mapper, HttpClient llmHttpClient,
                                     LlmRequestBodyBuilder bodyBuilder) {
        return new OpenAiCompatibleClient(
                "deepseek",                              // provider 编码
                "sk-xxx",                                // API Key（明文；密文场景见 11.7）
                "https://api.deepseek.com/v1/chat/completions",  // baseUrl
                mapper, llmHttpClient, bodyBuilder);
    }
}
```

`OpenAiCompatibleClient(providerCode, apiKey, baseUrl, objectMapper, httpClient, bodyBuilder)` 覆盖所有遵循 OpenAI Chat Completions 协议的供应商（DeepSeek / 智谱 / 豆包 / 通义 / LongCat 等）。

## 11.4 同步对话示例

`AgentEngine` 是框架统一入口（`core` 包）：

```java
package com.gewu.app.agent.demo;

import com.gewu.agent.engine.core.AgentEngine;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.llm.model.LlmResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SyncChatDemo {

    @Autowired
    private AgentEngine agentEngine;

    public String ask(String question) {
        LlmResponse response = agentEngine.execute(AgentTask.builder()
                .agentId(null)                  // 直接对话模式，agentId 可空
                .modelProvider("deepseek")      // 显式指定供应商（命中注册的 Bean）
                .modelName("deepseek-chat")
                .message(question)
                .agentMode("expert")
                .maxTokens(2048)
                .build());
        return response.getContent();          // 助手回复文本
    }
}
```

`AgentTask` 字段：`agentId`(可空直连)/`sessionId`/`userId`/`message`/`history`(空从 SessionContextService 加载)/`modelProvider`/`modelName`/`agentMode`(assistant|expert|creative|precise)/`thinkingStyle`/`temperature`/`maxTokens`。`execute` 返回完整 `LlmResponse`（同步阻塞）。

## 11.5 流式 SSE 对话示例

用 Spring MVC 把引擎流式输出转成 SSE 推给前端：

```java
package com.gewu.app.agent.demo;

import com.gewu.agent.engine.core.AgentEngine;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.core.event.AgentEvent;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;

import java.io.OutputStream;

@RestController
@RequestMapping("/api/agent")
public class StreamChatController {

    private final AgentEngine agentEngine;

    public StreamChatController(AgentEngine agentEngine) {
        this.agentEngine = agentEngine;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chatStream(@RequestBody ChatRequest req) {
        Flux<AgentEvent> stream = agentEngine.executeStream(AgentTask.builder()
                .agentId(null)
                .modelProvider("deepseek")
                .modelName("deepseek-chat")
                .sessionId(req.sessionId())
                .userId(req.userId())
                .message(req.message())
                .agentMode("expert")
                .build());

        StreamingResponseBody body = out -> {
            // SSE 演示：完整正确的前缀/双换行见 [13 事件协议]
            stream.doOnNext(event -> writeSse(out, event))
                  .doFinally(sig -> out.flush())
                  .blockLast();
        };
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }

    private void writeSse(OutputStream out, AgentEvent event) {
        try {
            String json = AgentEventJson.toJson(event);   // 使用方 JSON 序列化工具
            out.write(("data: " + json + "\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) { }
    }

    public record ChatRequest(String sessionId, String userId, String message) { }
}
```

`executeStream` 返回 `Flux<AgentEvent>`，事件类型（`AgentEvent` 常量）：`status`/`thinking`/`content`/`tool_call`/`tool_executing`/`tool_result`/`done`/`error`，字段含 `content`/`reasoning`/`toolCall`/`toolResult`/`errorMessage`。编排层扩展事件见 [13 事件协议](13-event-protocol.md)。SSE 传输格式为 `data: <json>\n\n`。

> 提示：框架不直接内置 SSE Controller，使用方按上例或用 `WebClient` / `SseEmitter` / `Flux` 直接返回均可。

## 11.6 注册自定义工具（@ToolProvider）

实现 `Tool` 接口并用 `@ToolProvider` 注解标记即可被自动扫描注册到 `ToolRegistry`：

```java
package com.gewu.app.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.llm.model.ToolDefinition;
import com.gewu.agent.engine.tool.Tool;
import com.gewu.agent.engine.tool.ToolContext;
import com.gewu.agent.engine.tool.ToolProvider;
import com.gewu.agent.engine.tool.ToolResult;
import org.springframework.stereotype.Component;

@ToolProvider(category = "GIT")     // @Component 派生，自动注册为 Spring Bean
public class GitCommitTool implements Tool {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GitService git;     // 使用方服务

    public GitCommitTool(GitService git) { this.git = git; }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("git_commit")
                .description("提交当前仓库变更")
                .parameters("""
                    {"type":"object","properties":{
                      "message":{"type":"string","description":"提交信息"}
                    },"required":["message"]}""")
                .build();
    }

    @Override
    public ToolResult invoke(String arguments, ToolContext context) {
        try {
            JsonNode args = mapper.readTree(arguments);
            String msg = args.path("message").asText();
            String rev = git.commit(context.getProjectId(), msg);
            return ToolResult.success("committed: " + rev, 0);
        } catch (Exception e) {
            return ToolResult.failure("git commit 失败: " + e.getMessage(), 0);
        }
    }
}
```

`ToolDefinition`（`llm.model` 包）：`name`/`description`/`parameters`(JSON Schema 字符串)。`ToolResult`：`success`/`output`/`error`/`duration`/`truncated`，静态工厂 `success(output, duration)` / `failure(error, duration)`。工具执行由 `ToolExecutor` 统一调度，先过安全管线（Schema 校验 → 权限评估 → SSRF → 代码扫描 → 审计），详见 [05 工具层](05-tool-layer.md)。

## 11.7 实现 PersistenceService 对接数据库

让 Agent 配置与执行记录落库（以 MyBatis 为例）：

```java
package com.gewu.app.agent.spi;

import com.gewu.agent.engine.spi.*;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;

@Component
public class DbPersistenceService implements PersistenceService {

    private final AgentMapper agentMapper;
    private final ToolMapper toolMapper;
    private final ExecutionMapper execMapper;

    public DbPersistenceService(AgentMapper a, ToolMapper t, ExecutionMapper e) {
        this.agentMapper = a; this.toolMapper = t; this.execMapper = e;
    }

    @Override
    public AgentSpec loadAgent(String agentId) {
        AgentDO d = agentMapper.selectById(agentId);
        if (d == null || d.getStatus() == null || d.getStatus() != 1) return null;
        return AgentSpec.builder()
                .id(d.getId()).name(d.getName()).description(d.getDescription())
                .modelProvider(d.getModelProvider()).modelName(d.getModelName())
                .modelConfig(d.getModelConfig()).systemPrompt(d.getSystemPrompt())
                .status(d.getStatus()).build();
    }

    @Override
    public List<ToolConfig> loadAgentTools(String agentId) {
        return toolMapper.selectEnabledByAgent(agentId).stream()
                .map(t -> ToolConfig.builder()
                        .toolName(t.getToolName()).description(t.getDescription())
                        .requestSchema(t.getRequestSchema()).toolType(t.getToolType())
                        .endpoint(t.getEndpoint()).mcpServerId(t.getMcpServerId())
                        .timeoutMs(t.getTimeoutMs()).sortOrder(t.getSortOrder())
                        .status(t.getStatus()).build())
                .toList();
    }

    @Override
    public ExecutionRecord createExecution(String agentId, String sessionId, String userId, String input) {
        String id = execMapper.insert(agentId, sessionId, userId, input, "running", Instant.now().toEpochMilli());
        return ExecutionRecord.builder()
                .id(id).agentId(agentId).sessionId(sessionId).userId(userId)
                .status("running").input(input).startedAt(Instant.now().toEpochMilli()).build();
    }

    @Override
    public void completeExecution(String executionId, String output, Integer tokensUsed) {
        long now = Instant.now().toEpochMilli();
        execMapper.markCompleted(executionId, "completed", output, tokensUsed, now);
    }

    @Override
    public void failExecution(String executionId, String errorMessage) {
        execMapper.markFailed(executionId, "failed", errorMessage, Instant.now().toEpochMilli());
    }

    @Override
    public ExecutionRecord getExecution(String executionId) {
        return execMapper.selectById(executionId);   // Mapper 结果直接映射为 ExecutionRecord
    }
}
```

注册为 `@Component` 即覆盖 `NoOpPersistenceService`。表结构按 `AgentSpec`/`ToolConfig`/`ExecutionRecord` 字段建模即可。

## 11.8 实现 LlmProvider 对接 model_provider 表

当供应商态从数据库管理时，实现 `LlmProvider` SPI，`LlmClientRegistry` 在静态 Bean 未命中时动态创建 `OpenAiCompatibleClient`：

```java
package com.gewu.app.agent.spi;

import com.gewu.agent.engine.llm.LlmProvider;
import com.gewu.agent.engine.llm.LlmProviderConfig;
import com.gewu.agent.engine.spi.ApiKeyDecryptor;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class DbLlmProvider implements LlmProvider {

    private final ModelProviderMapper mapper;
    private final ApiKeyDecryptor decryptor;        // 注入使用方解密实现（国密 SM4 等）

    public DbLlmProvider(ModelProviderMapper mapper, ApiKeyDecryptor decryptor) {
        this.mapper = mapper; this.decryptor = decryptor;
    }

    @Override
    public LlmProviderConfig getProviderConfig(String providerCode) {
        ModelProviderDO d = mapper.selectByCode(providerCode);
        if (d == null || d.getStatus() == null || d.getStatus() != 1) return null;
        // api_key 列存密文，运行时解密为明文交给 Registry
        String apiKey = decryptor.decrypt(d.getApiKey());
        return new LlmProviderConfig(providerCode, apiKey, d.getBaseUrl());
    }

    @Override
    public List<String> listProviderCodes() {
        return mapper.selectAllEnabledCodes();      // 预热 / 校验
    }
}
```

`LlmProviderConfig` 是 **record**：`record LlmProviderConfig(String providerCode, String apiKey, String baseUrl)`。`apiKey` 必须为**已解密明文**。同一项目可同时保留静态 `LlmClient` Bean（专用协议）+ 动态 `LlmProvider`（OpenAI 兼容供应商），`LlmClientRegistry` 查找顺序：静态 Bean → 动态缓存 → 动态创建。

## 11.9 运行验证步骤

```bash
# 1. 编译模块（确保 agent-engine 与使用方项目一同编译）
mvn -q -pl gewu-agent-engine install -DskipTests

# 2. 启动使用方 Spring Boot 应用
mvn -q spring-boot:run
#   日志应出现：
#   AgentEngineAutoConfiguration ... NoOpPersistenceService / NoOpLlmProvider ...
#   LlmClientRegistry 已注册供应商: [deepseek]
#   ToolRegistry 已注册工具: [git_commit]

# 3. 同步对话（11.4）
curl -s -X POST http://localhost:8080/api/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"用 Java 写快速排序","provider":"deepseek"}'

# 4. 流式 SSE（11.5）
curl -N -X POST http://localhost:8080/api/agent/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"s1","userId":"u1","message":"分析这段代码的性能瓶颈"}'
# 期望依次输出 data: {"type":"status",...} / {"type":"thinking",...} /
#                       {"type":"content",...} / {"type":"done",...} 等 SSE 行
```

验证检查清单：

| 检查项 | 期望 |
|--------|------|
| 启动日志含 `AgentEngineAutoConfiguration` 装配 | ✓ |
| 未实现 SPI 时仍可对话（走 NoOp） | ✓ |
| 同步 `execute` 返回 `LlmResponse.getContent()` 非空 | ✓ |
| 流式 `executeStream` 按 `content` 增量推送 SSE 直到 `done` | ✓ |
| `@ToolProvider` 工具出现在 `ToolRegistry` 日志 | ✓ |
| 实现 `PersistenceService` 后 `execution_record` 表有记录 | ✓ |
| 实现 `LlmProvider` 后动态供应商（如 `qwen`）可对话 | ✓ |

## 11.10 小结

接入引擎的最短路径只四步：引依赖、配 yml、注册一个 `LlmClient` Bean、调用 `AgentEngine.execute/executeStream`。进阶能力按需叠加：`@ToolProvider` 加工具、实现 `PersistenceService` 落库、实现 `LlmProvider` 动态供应商。所有 SPI 均有 NoOp 默认，可渐进接入而无需一次到位。完整 SPI 速查见 [10 SPI 规范](10-spi-reference.md)，深度扩展见 [12 扩展指南](12-extension-guide.md)，事件字段见 [13 事件协议](13-event-protocol.md)。