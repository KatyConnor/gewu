package com.gewu.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * OpenAI 兼容客户端 — 支持所有遵循 OpenAI API 格式的 LLM 供应商。
 * <p>
 * 适用于 DeepSeek、智谱（zhipu）、豆包（doubao）、LongCat 等 OpenAI 兼容接口。
 * 通过供应商的 base_url 和 api_key 动态配置，无需为每个供应商创建专用 Client。
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

    public OpenAiCompatibleClient(String providerCode, String apiKey, String baseUrl,
                                   ObjectMapper objectMapper, HttpClient httpClient,
                                   LlmRequestBodyBuilder bodyBuilder) {
        this.providerCode = providerCode;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.bodyBuilder = bodyBuilder;
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
                .timeout(Duration.ofSeconds(120))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            // 检查 HTTP 响应状态码，非 2xx 视为错误
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
                // 强制 HTTP/1.1：HTTP/2 的流控机制可能导致 SSE 响应体被缓冲，
                // HTTP/1.1 的 chunked transfer encoding 更适合 SSE 流式传输
                .version(HttpClient.Version.HTTP_1_1)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                // 流式请求不设 per-request timeout：推理模型（DeepSeek-R1 / LongCat-2.0）
                // 的推理+生成过程常超 120s，固定 timeout 会截断流式传输导致回复不完整。
                // 连接建立阶段由 HttpClient.connectTimeout(30s) 兜底，
                // 整体时长由 Spring MVC async request-timeout 兜底。
                .build();

        log.info("{} chatStream 请求: url={}, model={}, apiKey={}, bodyLen={}, httpVersion=HTTP_1_1",
                providerCode, endpointUrl(), request.getModel(),
                (apiKey == null || apiKey.isBlank()) ? "未配置" : "已配置",
                body.length());

        // 阻塞 I/O 必须调度到 boundedElastic 线程池，避免阻塞 Netty 事件循环
        return Flux.<LlmChunk>create(sink -> {
            try {
                log.info("{} 发送 HTTP 请求到 {}", providerCode, baseUrl);
                long startMs = System.currentTimeMillis();
                HttpResponse<java.io.InputStream> response = httpClient.send(httpRequest,
                        HttpResponse.BodyHandlers.ofInputStream());
                long elapsedMs = System.currentTimeMillis() - startMs;
                log.info("{} 收到 HTTP 响应: statusCode={}, 耗时={}ms", providerCode, response.statusCode(), elapsedMs);

                // 检查 HTTP 响应状态码，非 2xx 视为错误
                int statusCode = response.statusCode();
                if (statusCode < 200 || statusCode >= 300) {
                    String errorBody;
                    try (java.io.InputStream is = response.body();
                         java.io.BufferedReader reader = new java.io.BufferedReader(
                                 new java.io.InputStreamReader(is))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        errorBody = sb.toString();
                    }
                    log.error("{} API 流式请求失败: statusCode={}, body={}", providerCode, statusCode, errorBody);
                    sink.error(new RuntimeException(providerCode + " API 认证失败或请求错误 (HTTP " + statusCode + ")"));
                    return;
                }

                // 使用 try-with-resources 确保 reader 和底层 InputStream 正确关闭
                try (java.io.InputStream is = response.body();
                     java.io.BufferedReader reader = new java.io.BufferedReader(
                             new java.io.InputStreamReader(is))) {
                    String line;
                    int chunkCount = 0;
                    long firstChunkMs = 0;
                    long lastLogMs = startMs;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data:")) {
                            String data = line.substring(5).trim();
                            if ("[DONE]".equals(data)) {
                                log.info("{} SSE 流结束: [DONE], 共 {} 个 chunk, 首chunk距响应={}ms, 总流式耗时={}ms",
                                        providerCode, chunkCount,
                                        firstChunkMs > 0 ? firstChunkMs - elapsedMs - startMs : -1,
                                        System.currentTimeMillis() - startMs);
                                break;
                            }
                            LlmChunk chunk = parseStreamChunk(data);
                            if (chunk != null) {
                                chunkCount++;
                                if (chunkCount == 1) {
                                    firstChunkMs = System.currentTimeMillis();
                                    log.info("{} SSE 首个 chunk 到达: 距离收到响应 {}ms", providerCode, firstChunkMs - startMs - elapsedMs);
                                }
                                // 每 200 个 chunk 记录一次，用于诊断是否增量到达
                                if (chunkCount % 200 == 0) {
                                    long now = System.currentTimeMillis();
                                    log.info("{} SSE 已读取 {} 个 chunk, 本批次 200 chunk 耗时={}ms", providerCode, chunkCount, now - lastLogMs);
                                    lastLogMs = now;
                                }
                                sink.next(chunk);
                            }
                        }
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

    private LlmResponse parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");

            LlmResponse.LlmResponseBuilder builder = LlmResponse.builder();

            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode firstChoice = choices.get(0);
                JsonNode message = firstChoice.path("message");

                String content = message.path("content").asText(null);
                // 兼容 reasoning_content 字段
                if (content == null || content.isEmpty()) {
                    content = message.path("reasoning_content").asText(null);
                }
                builder.content(content);
                builder.finishReason(firstChoice.path("finish_reason").asText(null));

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

                // 标准 content 字段（最终回复内容）
                String content = delta.path("content").asText(null);
                boolean hasContent = content != null && !content.isEmpty();
                // reasoning_content 字段（LongCat/DeepSeek-R1/Qwen3 等推理模型的思考过程）
                // 与 content 分离，不混入 delta，前端可分别展示思考过程和最终回复
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