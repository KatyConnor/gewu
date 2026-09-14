package com.gewu.agent.engine.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.llm.model.LlmChunk;
import com.gewu.agent.engine.llm.model.LlmRequest;
import com.gewu.agent.engine.llm.model.LlmResponse;
import com.gewu.agent.engine.llm.model.ToolCall;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容客户端 - 支持所有遵循 OpenAI Chat Completions 协议的 LLM 供应商。
 * <p>适用于 DeepSeek、智谱、豆包、LongCat、通义千问等。通过供应商 base_url 与 api_key 动态配置，
 * 无需为每个供应商创建专用 Client。支持同步 / 流式响应、推理内容（reasoning_content）分离、
 * 工具调用（function calling）增量累积。
 *
 * @since 1.0.0
 */
@Slf4j
public class OpenAiCompatibleClient implements LlmClient {

    private final String providerCode;
    private final String apiKey;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final LlmRequestBodyBuilder bodyBuilder;
    /** 同步请求超时（由 agent.engine.llm.request-timeout 配置） */
    private final Duration requestTimeout;

    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(120);

    /** 流式空闲看门狗（毫秒，0=禁用）：SSE 流无数据超过该时长即中断读取，防上游挂死 */
    private volatile long streamIdleTimeoutMs = DEFAULT_STREAM_IDLE_TIMEOUT_MS;

    private static final long DEFAULT_STREAM_IDLE_TIMEOUT_MS = 180_000L;

    /** 看门狗共享调度线程（daemon，进程级单例） */
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG_SCHEDULER =
            java.util.concurrent.Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "llm-stream-watchdog");
                t.setDaemon(true);
                return t;
            });

    public void setStreamIdleTimeoutMs(long streamIdleTimeoutMs) {
        this.streamIdleTimeoutMs = streamIdleTimeoutMs;
    }

    public OpenAiCompatibleClient(String providerCode, String apiKey, String baseUrl,
                                   ObjectMapper objectMapper, HttpClient httpClient,
                                   LlmRequestBodyBuilder bodyBuilder) {
        this(providerCode, apiKey, baseUrl, objectMapper, httpClient, bodyBuilder, DEFAULT_REQUEST_TIMEOUT);
    }

    public OpenAiCompatibleClient(String providerCode, String apiKey, String baseUrl,
                                   ObjectMapper objectMapper, HttpClient httpClient,
                                   LlmRequestBodyBuilder bodyBuilder, Duration requestTimeout) {
        this.providerCode = providerCode;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.bodyBuilder = bodyBuilder;
        this.requestTimeout = requestTimeout != null ? requestTimeout : DEFAULT_REQUEST_TIMEOUT;
    }

    @Override
    public String getProvider() {
        return providerCode;
    }

    /**
     * 解析聊天补全端点 URL：兼容两种 base_url 配置——
     * 完整端点（以 /chat/completions 结尾）直接使用；
     * 裸 Base URL（如 https://api.openai.com/v1）自动补全请求路径。
     */
    private String endpointUrl() {
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        String body = bodyBuilder.buildBody(request, false, providerCode);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl()))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(requestTimeout)
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                log.error("{} API 同步请求失败: statusCode={}, body={}", providerCode, statusCode, response.body());
                throw new RuntimeException(providerCode + " API 认证失败或请求错误 (HTTP " + statusCode + ")");
            }
            return parseResponse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(providerCode + " API 请求被中断", e);
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException(providerCode + " API 请求失败", e);
        }
    }

    @Override
    public Flux<LlmChunk> chatStream(LlmRequest request) {
        String body = bodyBuilder.buildBody(request, true, providerCode);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl()))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                // 强制 HTTP/1.1：HTTP/2 流控可能缓冲 SSE 响应体，HTTP/1.1 chunked 更适合流式传输
                .version(HttpClient.Version.HTTP_1_1)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                // 流式不设 per-request timeout，由 HttpClient.connectTimeout 与 async request-timeout 兜底
                .build();

        log.info("{} chatStream 请求: url={}, model={}, apiKey={}, bodyLen={}, httpVersion=HTTP_1_1",
                providerCode, endpointUrl(), request.getModel(),
                (apiKey == null || apiKey.isBlank()) ? "未配置" : "已配置",
                body.length());

        // 阻塞 I/O 调度到 boundedElastic，避免阻塞事件循环
        return Flux.<LlmChunk>create(sink -> {
            try {
                long startMs = System.currentTimeMillis();
                HttpResponse<java.io.InputStream> response = httpClient.send(httpRequest,
                        HttpResponse.BodyHandlers.ofInputStream());
                long elapsedMs = System.currentTimeMillis() - startMs;
                log.info("{} 收到 HTTP 响应: statusCode={}, 耗时={}ms", providerCode, response.statusCode(), elapsedMs);

                int statusCode = response.statusCode();
                if (statusCode < 200 || statusCode >= 300) {
                    String errorBody = drainStream(response.body());
                    log.error("{} API 流式请求失败: statusCode={}, body={}", providerCode, statusCode, errorBody);
                    sink.error(new RuntimeException(providerCode + " API 认证失败或请求错误 (HTTP " + statusCode + ")"));
                    return;
                }

                try (java.io.InputStream is = withIdleWatchdog(response.body(), streamIdleTimeoutMs);
                     java.io.BufferedReader reader = new java.io.BufferedReader(
                             new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    int chunkCount = 0;
                    boolean sawDone = false;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data:")) {
                            String data = line.substring(5).trim();
                            if ("[DONE]".equals(data)) {
                                sawDone = true;
                                log.info("{} SSE 流结束: [DONE], 共 {} 个 chunk, 总耗时={}ms",
                                        providerCode, chunkCount, System.currentTimeMillis() - startMs);
                                break;
                            }
                            LlmChunk chunk = parseStreamChunk(data);
                            if (chunk != null) {
                                chunkCount++;
                                sink.next(chunk);
                            }
                        }
                    }
                    if (!sawDone) {
                        // EOF 但未收到 [DONE]：上游连接被中断（代理超时/连接静默关闭），
                        // 按错误处理而非静默 complete，让截断可见（S9）
                        log.error("{} SSE 流中断：连接结束但未收到 [DONE]，已收 {} 个 chunk, 总耗时={}ms",
                                providerCode, chunkCount, System.currentTimeMillis() - startMs);
                        sink.error(new RuntimeException(
                                providerCode + " 上游连接中断（SSE 流结束但未收到 [DONE]）"));
                        return;
                    }
                    log.info("{} SSE 读取完成: 共 {} 个 chunk", providerCode, chunkCount);
                    sink.complete();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("{} chatStream 被中断", providerCode, e);
                sink.error(e);
            } catch (Exception e) {
                log.error("{} chatStream 异常: {}", providerCode, e.getMessage(), e);
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String drainStream(java.io.InputStream is) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return "<unreadable>";
        }
    }

    /**
     * 流式空闲看门狗：包装响应流，超过 idleTimeoutMs 无任何数据（读取阻塞无进展）
     * 时主动关闭底层流，使阻塞中的 readLine 抛出 IOException 中断读取，避免
     * boundedElastic 线程因上游挂死而永久占用（S9）。
     */
    private java.io.InputStream withIdleWatchdog(java.io.InputStream delegate, long timeoutMs) {
        if (timeoutMs <= 0) {
            return delegate;
        }
        return new java.io.FilterInputStream(delegate) {
            private volatile long lastActivityMs = System.currentTimeMillis();
            private final java.util.concurrent.ScheduledFuture<?> check = WATCHDOG_SCHEDULER
                    .scheduleWithFixedDelay(() -> {
                        if (System.currentTimeMillis() - lastActivityMs >= timeoutMs) {
                            log.error("{} 流式空闲超时（{}ms 无数据），中断读取", providerCode, timeoutMs);
                            try {
                                delegate.close();
                            } catch (Exception ignored) {
                                // 关闭失败只能尽力而为：读取线程可能继续阻塞
                            }
                        }
                    }, timeoutMs, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);

            @Override
            public int read() throws java.io.IOException {
                int b = super.read();
                if (b >= 0) {
                    lastActivityMs = System.currentTimeMillis();
                }
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws java.io.IOException {
                int n = super.read(b, off, len);
                if (n > 0) {
                    lastActivityMs = System.currentTimeMillis();
                }
                return n;
            }

            @Override
            public void close() throws java.io.IOException {
                check.cancel(false);
                super.close();
            }
        };
    }

    private LlmResponse parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");

            LlmResponse.LlmResponseBuilder builder = LlmResponse.builder();

            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode firstChoice = choices.get(0);
                JsonNode message = firstChoice.path("message");

                String content = message.path("content").asText(null);
                JsonNode toolCallsNode = message.path("tool_calls");
                boolean hasToolCalls = toolCallsNode.isArray() && !toolCallsNode.isEmpty();
                if ((content == null || content.isEmpty()) && !hasToolCalls) {
                    // 仅当无正式回复且无工具调用时才以思考内容兜底（原实现会把
                    // "模型只输出思考"误当正式答案；有工具调用时 content 为空是协议正常态）
                    content = message.path("reasoning_content").asText(null);
                    if (content != null && !content.isEmpty()) {
                        builder.reasoningFallback(true);
                        log.warn("{} 响应无正式 content，以 reasoning_content 兜底（疑似 token 耗尽未产出回复）",
                                providerCode);
                    }
                }
                builder.content(content);
                builder.finishReason(firstChoice.path("finish_reason").asText(null));

                JsonNode toolCalls = toolCallsNode;
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

            if (!choices.isArray() || choices.isEmpty()) {
                log.error("{} 响应缺少 choices 字段（供应商配置错误/协议不匹配/鉴权失败）: bodyHead={}",
                        providerCode, responseBody != null && responseBody.length() > 200
                                ? responseBody.substring(0, 200) : responseBody);
            }

            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode()) {
                builder.usage(LlmResponse.Usage.builder()
                        .promptTokens(usage.path("prompt_tokens").asInt(0))
                        .completionTokens(usage.path("completion_tokens").asInt(0))
                        .totalTokens(usage.path("total_tokens").asInt(0))
                        .build());
            }

            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException("解析 " + providerCode + " 响应失败", e);
        }
    }

    private LlmChunk parseStreamChunk(String data) {
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode choices = root.path("choices");

            LlmChunk.LlmChunkBuilder builder = LlmChunk.builder();

            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode firstChoice = choices.get(0);
                JsonNode delta = firstChoice.path("delta");

                String content = delta.path("content").asText(null);
                boolean hasContent = content != null && !content.isEmpty();
                String reasoningContent = delta.path("reasoning_content").asText(null);
                boolean hasReasoning = reasoningContent != null && !reasoningContent.isEmpty();

                if (hasContent) {
                    builder.delta(content);
                }
                if (hasReasoning) {
                    builder.reasoning(reasoningContent);
                }

                JsonNode toolCalls = delta.path("tool_calls");
                if (toolCalls.isArray() && !toolCalls.isEmpty()) {
                    JsonNode tc = toolCalls.get(0);
                    JsonNode function = tc.path("function");
                    builder.toolCallDelta(LlmChunk.ToolCallDelta.builder()
                            .id(tc.path("id").asText(null))
                            .name(function.path("name").asText(null))
                            .arguments(function.path("arguments").asText(null))
                            .build());
                }

                String finishReason = firstChoice.path("finish_reason").asText(null);
                if (finishReason != null && !"null".equals(finishReason)) {
                    builder.finishReason(finishReason);
                }
            }

            return builder.build();
        } catch (Exception e) {
            log.warn("解析 {} 流式 chunk 失败: {}", providerCode, data, e);
            return null;
        }
    }
}
