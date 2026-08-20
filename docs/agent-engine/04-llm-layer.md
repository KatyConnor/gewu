# 格物 Agent 引擎 — LLM 层

> 本篇详解 `llm` 包：`LlmClient` 接口、`LlmRequest`/`LlmResponse`/`LlmChunk`/`Message`/`ToolDefinition`/`ToolCall` 模型、`OpenAiCompatibleClient` 实现（同步/流式/SSE 解析/reasoning_content 分离）、`LlmClientRegistry` 多供应商路由（静态注册+动态 SPI+缓存）、`LlmProvider` SPI、`LlmRequestBodyBuilder`。
> 包：`com.gewu.agent.engine.llm`（模型在 `llm.model`）。

## 1. LlmClient 接口设计

LLM 客户端的统一契约只有三个方法，刻意保持极简，覆盖"我是谁 + 同步 + 流式"三件事：

```java
package com.gewu.agent.engine.llm;

import reactor.core.publisher.Flux;

public interface LlmClient {
    /** 供应商标识，如 qwen / deepseek / zhipu */
    String getProvider();

    /** 同步对话：发送请求并阻塞等待完整响应 */
    LlmResponse chat(LlmRequest request);

    /** 流式对话：返回增量分块流，适配 SSE 实时推送 */
    Flux<LlmChunk> chatStream(LlmRequest request);
}
```

设计要点：

- `getProvider()` 返回**供应商编码**而非模型名，用于注册中心按 provider 路由；模型名在每个 `LlmRequest.model` 中携带，因此同一个客户端实例可服务同供应商的多个模型。
- 同步与流式分离：上层 `ReactAgentExecutor` 根据是否流式分别调用 `chat` / `chatStream`，避免在接口里用 `stream` 标志分支。
- 流式返回 `Flux<LlmChunk>`（Reactor），契合 Spring 生态；不做任何业务语义解析，只做协议解包。

## 2. 模型详解

六个模型类全部位于 `llm.model`，使用 Lombok `@Data @Builder`，可直接 `builder()` 链式构造。

### 2.1 LlmRequest（请求）

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class LlmRequest {
    private String                 model;        // 模型名，如 qwen-plus / deepseek-chat
    private List<Message>          messages;     // 对话消息列表
    private List<ToolDefinition>   tools;        // 可用工具定义
    private Double                 temperature;  // 采样温度
    private Integer                maxTokens;    // 最大生成 token 数
    private Boolean                stream;       // 是否流式响应
}
```

### 2.2 Message（消息）

遵循 OpenAI Chat Completions 消息格式，`role` 可为 `system` / `user` / `assistant` / `tool`：

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Message {
    private String role;          // system / user / assistant / tool
    private String content;       // 消息内容
    private String toolCallId;    // tool 角色消息关联的工具调用 ID
    private String name;          // tool 角色消息关联的工具名
}
```

`toolCallId` 与 `name` 仅在 `role="tool"` 时使用——回灌工具结果时必须带上 `toolCallId` 以便 LLM 关联。

### 2.3 ToolDefinition / ToolCall（工具定义与工具调用）

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ToolDefinition {
    private String name;          // 工具名称
    private String description;   // 工具描述
    private String parameters;   // JSON Schema 格式的参数定义（字符串）
}

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ToolCall {
    private String id;            // 调用 ID（由 LLM 分配，关联工具结果）
    private String name;          // 工具名称
    private String arguments;    // 参数（JSON 字符串）
}
```

`ToolDefinition.parameters` 是**字符串形式的 JSON Schema**，由 `LlmRequestBodyBuilder` 在序列化时 `objectMapper.readTree` 解析为 JSON 节点嵌入请求体。

### 2.4 LlmResponse（同步响应）

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class LlmResponse {
    private String           content;       // 文本回复内容
    private List<ToolCall>   toolCalls;     // LLM 请求执行的工具调用(为空=无需工具，回复即最终答案)
    private Usage            usage;         // token 用量统计
    private String           finishReason;  // stop / length / tool_calls / content_filter

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Usage {
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;
    }
}
```

`finishReason` 取值：`stop`（正常结束）/ `length`（达到 token 上限被截断）/ `tool_calls`（请求工具调用）/ `content_filter`（内容被过滤）。上层据此判断是否继续循环与提示截断。

### 2.5 LlmChunk（流式分块）

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class LlmChunk {
    private String          delta;          // 正式回复增量内容
    private String          reasoning;      // 推理/思考内容增量(reasoning_content 字段，与正式回复分离)
    private ToolCallDelta   toolCallDelta;  // 工具调用增量
    private String          finishReason;   // 结束原因(通常在最后一个 chunk)

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ToolCallDelta {
        private String id;
        private String name;
        private String arguments;
    }
}
```

`reasoning` 字段是本框架的关键差异化点：来自 OpenAI 兼容协议的 `reasoning_content` 字段（DeepSeek-R1、通义千问等支持思考链的模型），与 `delta`（正式回复）**分离传输**，使上层能区分"思考过程"与"正式回复"，分别映射为 `THINKING` / `CONTENT` 两种事件。

## 3. OpenAiCompatibleClient 实现

通用实现覆盖所有遵循 OpenAI Chat Completions 协议的供应商。构造参数：

```java
public class OpenAiCompatibleClient implements LlmClient {
    private final String               providerCode;
    private final String               apiKey;
    private final String               baseUrl;       // Chat Completions endpoint
    private final ObjectMapper         objectMapper;
    private final HttpClient           httpClient;
    private final LlmRequestBodyBuilder bodyBuilder;

    public OpenAiCompatibleClient(String providerCode, String apiKey, String baseUrl,
                                   ObjectMapper objectMapper, HttpClient httpClient,
                                   LlmRequestBodyBuilder bodyBuilder) { ... }
}
```

### 3.1 同步 chat

- 请求体由 `bodyBuilder.buildBody(request, false, providerCode)` 生成。
- `POST {baseUrl}`，Header `Authorization: Bearer {apiKey}` / `Content-Type: application/json`，per-request timeout 120s。
- 2xx 之外抛 `RuntimeException("{provider} API 认证失败或请求错误 (HTTP {code})")`，`InterruptedException` 恢复中断标志后包装抛出。
- 响应解析见下文 `parseResponse`。

### 3.2 流式 chatStream

```java
public Flux<LlmChunk> chatStream(LlmRequest request) {
    String body = bodyBuilder.buildBody(request, true, providerCode);
    HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .version(HttpClient.Version.HTTP_1_1)   // 强制 HTTP/1.1：HTTP/2 流控可能缓冲 SSE 响应体
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return Flux.<LlmChunk>create(sink -> { /* SSE 读取 */ })
            .subscribeOn(Schedulers.boundedElastic()); // 阻塞 I/O 调度到 boundedElastic
}
```

SSE 读取核心（`Flux.create` 内部）：

```java
try (InputStream is = response.body();
     BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
    String line;
    while ((line = reader.readLine()) != null) {
        if (line.startsWith("data:")) {
            String data = line.substring(5).trim();
            if ("[DONE]".equals(data)) break;          // 流结束标志
            LlmChunk chunk = parseStreamChunk(data);
            if (chunk != null) sink.next(chunk);
        }
    }
    sink.complete();
}
```

要点：

- **强制 HTTP/1.1**：注释明确说明 HTTP/2 的流控可能缓冲 SSE 响应体，HTTP/1.1 的 chunked 传输更适合流式。
- **`subscribeOn(Schedulers.boundedElastic())`**：阻塞 `BufferedReader.readLine()` 不能在事件循环线程跑，故调度到弹性线程池。
- 错误响应先 `drainStream` 读出 body 再 `sink.error`，避免连接泄漏。
- `[DONE]` 是 OpenAI SSE 流的标准结束哨兵。

### 3.3 同步响应解析 parseResponse

```java
private LlmResponse parseResponse(String responseBody) {
    JsonNode root = objectMapper.readTree(responseBody);
    JsonNode choices = root.path("choices");
    if (choices.isArray() && !choices.isEmpty()) {
        JsonNode firstChoice = choices.get(0);
        JsonNode message = firstChoice.path("message");

        // reasoning_content 分离：content 为空时回退到 reasoning_content
        String content = message.path("content").asText(null);
        if (content == null || content.isEmpty()) {
            content = message.path("reasoning_content").asText(null);
        }
        builder.content(content);
        builder.finishReason(firstChoice.path("finish_reason").asText(null));

        // tool_calls
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray() && !toolCalls.isEmpty()) {
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode tc : toolCalls) {
                JsonNode function = tc.path("function");
                calls.add(ToolCall.builder()
                        .id(tc.path("id").asText(null))
                        .name(function.path("name").asText(null))
                        .arguments(function.path("arguments").asText(null))
                        .build());
            }
            builder.toolCalls(calls);
        }
    }
    // usage: prompt_tokens / completion_tokens / total_tokens
}
```

### 3.4 流式 chunk 解析 parseStreamChunk

```java
private LlmChunk parseStreamChunk(String data) {
    JsonNode root = objectMapper.readTree(data);
    JsonNode delta = choices[0].path("delta");

    String content = delta.path("content").asText(null);
    String reasoningContent = delta.path("reasoning_content").asText(null);
    if (hasContent)    builder.delta(content);
    if (hasReasoning)  builder.reasoning(reasoningContent);   // reasoning_content 分离

    JsonNode toolCalls = delta.path("tool_calls");
    if (toolCalls.isArray() && !toolCalls.isEmpty()) {
        // 工具调用增量：取第一个 entry 的 id / function.name / function.arguments
        builder.toolCallDelta(ToolCallDelta.builder()
                .id(tc.path("id").asText(null))
                .name(function.path("name").asText(null))
                .arguments(function.path("arguments").asText(null))
                .build());
    }
    String finishReason = firstChoice.path("finish_reason").asText(null);
    if (finishReason != null && !"null".equals(finishReason)) builder.finishReason(finishReason);
}
```

### 3.5 reasoning_content 分离

| 字段来源 | 映射为 | 上层事件 |
|---------|--------|---------|
| `delta.content` / `message.content` | `LlmChunk.delta` / `LlmResponse.content` | `CONTENT` |
| `delta.reasoning_content` / `message.reasoning_content`（content 为空时回退）| `LlmChunk.reasoning` | `THINKING` |
| `delta.tool_calls` 增量 | `LlmChunk.toolCallDelta` | 累积后 `TOOL_CALL` |
| `finish_reason` | `LlmChunk.finishReason` / `LlmResponse.finishReason` | 截断判断 |

同步场景的回退逻辑：当模型只产思考未产正文时，`content` 为空，则回退取 `reasoning_content` 作为正文，避免上层拿到空回复。

### 3.6 同步时序图

```
ReactAgentExecutor        OpenAiCompatibleClient        HttpClient           LLM
   │ chat(LlmRequest)            │                         │                   │
   │ ─────────────────────────> │ buildBody(stream=false) │                   │
   │                            │ POST + Bearer apiKey ──> │                   │
   │                            │                         │ ── HTTPS POST ──> │
   │                            │                         │ <── 200 + body ─── │
   │                            │ <── HttpResponse ─────  │                   │
   │                            │ parseResponse(body)                          │
   │ <── LlmResponse ───────── │                                              │
```

### 3.7 流式时序图

```
ReactAgentExecutor     OpenAiCompatibleClient          HttpClient         LLM(SSE)
   │ chatStream(req)            │                          │                   │
   │ ────────────────────────> │ buildBody(stream=true)   │                   │
   │                            │ POST HTTP_1_1 + Accept ─> │                   │
   │                            │ Flux.create(sink)         │ ── HTTPS POST ──> │
   │                            │   subscribeOn(boundedElastic)                 │
   │                            │                          │ <── 200 stream ─── │
   │                            │   readLine: "data: {...}"                    │
   │                            │   parseStreamChunk -> sink.next(LlmChunk) <─> │  (重复)
   │                            │   "data: [DONE]" -> break                    │
   │                            │   sink.complete()                             │
   │ <── Flux<LlmChunk> ─────── │                                               │
   │  map chunk -> AgentEvent                                                   │
```

## 4. LlmClientRegistry 多供应商路由

注册中心替代工厂模式，管理并路由多供应商客户端。查找顺序是三层：

```java
public class LlmClientRegistry {
    private final Map<String, LlmClient> staticClients;          // Spring 注入的 Bean
    private final Map<String, LlmClient> dynamicCache = new ConcurrentHashMap<>();
    private final LlmProvider          provider;                // SPI
    private final ObjectMapper        objectMapper;
    private final HttpClient           httpClient;
    private final LlmRequestBodyBuilder bodyBuilder;

    public LlmClient getClient(String provider) {
        if (provider == null || provider.isBlank())
            throw AgentEngineException.of("PARAM_INVALID", "LLM 提供商不能为空");

        // 1. 静态注册（Spring 注入的专用客户端）
        LlmClient staticClient = staticClients.get(provider);
        if (staticClient != null) return staticClient;

        // 2. 动态缓存
        LlmClient cachedClient = dynamicCache.get(provider);
        if (cachedClient != null) return cachedClient;

        // 3. 通过 LlmProvider SPI 动态创建并缓存
        LlmClient dynamicClient = createDynamicClient(provider);
        if (dynamicClient != null) {
            dynamicCache.put(provider, dynamicClient);
            return dynamicClient;
        }
        throw AgentEngineException.of("PROVIDER_NOT_FOUND", "不支持的 LLM 提供商: " + provider);
    }
}
```

动态创建逻辑：

```java
private LlmClient createDynamicClient(String providerCode) {
    if (provider == null) return null;
    try {
        LlmProviderConfig cfg = provider.getProviderConfig(providerCode);
        if (cfg == null || cfg.baseUrl() == null || cfg.baseUrl().isBlank()) return null;
        String apiKey = cfg.apiKey() != null ? cfg.apiKey() : "";
        return new OpenAiCompatibleClient(
                providerCode, apiKey, cfg.baseUrl(), objectMapper, httpClient, bodyBuilder);
    } catch (Exception e) {
        log.warn("从 LlmProvider 加载供应商 {} 失败: {}", providerCode, e.getMessage());
        return null;
    }
}
```

路由与缓存机制总表：

| 层 | 来源 | 命中条件 | 是否缓存 | 失效方式 |
|----|------|----------|----------|----------|
| 静态 | Spring 注入的 `List<LlmClient>` Bean，按 `getProvider()` 索引为 unmodifiableMap | provider 编码精确匹配 | 永驻（Bean 生命周期）| Bean 销毁 |
| 动态 | `LlmProvider` SPI 返回 `LlmProviderConfig`，`OpenAiCompatibleClient` 实例 | 静态未命中且缓存未命中时创建 | `ConcurrentHashMap` 缓存 | `evictClient(providerCode)` |
| 枚举 | `listProviderCodes()` 合并三层 | — | — | — |

`evictClient(providerCode)` 提供配置变更后的失效入口；`listProviderCodes()` 合并静态键 + 动态缓存键 + `provider.listProviderCodes()` 去重，用于预热与校验。构造器使用 `Collectors.toUnmodifiableMap`，遇重复 `getProvider` 保留前者（`(a, b) -> a`）。

## 5. LlmProvider SPI 与 LlmProviderConfig

```java
public interface LlmProvider {
    LlmProviderConfig getProviderConfig(String providerCode);  // 未配置返回 null
    default List<String> listProviderCodes() { return List.of(); }
}

public record LlmProviderConfig(String providerCode, String apiKey, String baseUrl) {}
```

契约要点：

- **不绑定数据库**：配置来源由使用方自行决定——数据库、配置中心、文件均可，框架只要求实现此接口。
- **apiKey 必须是已解密明文**：注释明确说明，解密由使用方在实现 `LlmProvider` 时完成（可借助 `ApiKeyDecryptor` SPI）。
- **不提供 → NoOpLlmProvider**：`spi.defaults.NoOpLlmProvider` 返回空，此时仅代码注册的静态客户端可用。

典型实现示例（从数据库读供应商配置）：

```java
@Component
public class DbLlmProvider implements LlmProvider {
    private final LlmSupplierRepository repo;
    private final ApiKeyDecryptor decryptor;

    @Override
    public LlmProviderConfig getProviderConfig(String code) {
        LlmSupplier s = repo.findByCode(code);
        if (s == null) return null;
        return new LlmProviderConfig(code, decryptor.decrypt(s.getEncryptedKey()), s.getBaseUrl());
    }
}
```

## 6. LlmRequestBodyBuilder

负责把内部请求模型序列化为 OpenAI 兼容的 JSON 请求体：

```java
public class LlmRequestBodyBuilder {
    private final ObjectMapper objectMapper;

    public String buildBody(LlmRequest request, boolean stream, String provider) {
        ObjectNode root = objectMapper.createObjectNode();
        addBasicParams(root, request, stream);   // model / temperature / max_tokens / stream
        addMessages(root, request.getMessages()); // messages 数组
        addTools(root, request.getTools());       // tools 数组(function calling)
        return serialize(root);
    }
}
```

`buildMessagesArray` 序列化每条 `Message`：`role`、`content`、`tool_call_id`（tool 角色消息）、`name`。`buildToolsArray` 按 OpenAI 格式将工具包装为 `{type:"function", function:{name, description, parameters}}`，其中 `parameters` 通过 `objectMapper.readTree(tool.getParameters())` 由字符串解析回 JSON 节点嵌入。

请求体结构示例：

```json
{
  "model": "deepseek-chat",
  "stream": true,
  "temperature": 0.3,
  "max_tokens": 8192,
  "messages": [
    {"role": "system", "content": "【专家模式】..."},
    {"role": "user", "content": "帮我查订单"},
    {"role": "assistant", "content": "..."},
    {"role": "tool", "tool_call_id": "call_x1", "name": "queryOrder", "content": "{\"status\":\"ok\"}"}
  ],
  "tools": [
    {"type": "function", "function": {"name": "queryOrder", "description": "查询订单", "parameters": {...JSON Schema...}}}
  ]
}
```

| 构建方法 | 产出 | 触发条件 |
|---------|------|----------|
| `addBasicParams` | `model` / `temperature` / `max_tokens` / `stream` | 字段非空才写入 |
| `buildMessagesArray` | `messages` 数组 | 始终 |
| `buildToolsArray` | `tools` 数组(function calling) | `tools` 非空才写入 |

## 7. 小结

LLM 层的张力在于"通用 vs 可替换"：`OpenAiCompatibleClient` 用一份实现吃下绝大多数 OpenAI 兼容供应商；`LlmClient` 接口与 `LlmProvider` SPI 又为新协议供应商与各业务方定制留好了口子。`reasoning_content` 分离让思考链模型能被一等公民式地驱动，`LlmClientRegistry` 的静态/动态/缓存三层路由让"代码优先、配置兜底"的接入模型成为可能，且全程不绑定数据库——这是框架从平台抽离时最关键的解耦决策之一。

---

上一篇：[03-core-engine.md](./03-core-engine.md) ｜ 下一篇：[05-tool-layer.md](./05-tool-layer.md)