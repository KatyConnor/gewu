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

    @Override
    public LlmResponse chat(LlmRequest request) {
        String body = bodyBuilder.buildBody(request, false, providerCode);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(120))
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
                .uri(URI.create(baseUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                // 强制 HTTP/1.1：HTTP/2 流控可能缓冲 SSE 响应体，HTTP/1.1 chunked 更适合流式传输
                .version(HttpClient.Version.HTTP_1_1)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                // 流式不设 per-request timeout，由 HttpClient.connectTimeout 与 async request-timeout 兜底
                .build();

        log.info("{} chatStream 请求: url={}, model={}, apiKey={}, bodyLen={}, httpVersion=HTTP_1_1",
                providerCode, baseUrl, request.getModel(),
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

                try (java.io.InputStream is = response.body();
                     java.io.BufferedReader reader = new java.io.BufferedReader(
                             new java.io.InputStreamReader(is))) {
                    String line;
                    int chunkCount = 0;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data:")) {
                            String data = line.substring(5).trim();
                            if ("[DONE]".equals(data)) {
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

    private LlmResponse parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");

            LlmResponse.LlmResponseBuilder builder = LlmResponse.builder();

            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode firstChoice = choices.get(0);
                JsonNode message = firstChoice.path("message");

                String content = message.path("content").asText(null);
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
