package com.gewu.infrastructure.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

@Slf4j
public class QwenClient implements LlmClient {

    private final String apiKey;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final LlmRequestBodyBuilder bodyBuilder;

    public QwenClient(String apiKey, String baseUrl, ObjectMapper objectMapper,
                      HttpClient httpClient, LlmRequestBodyBuilder bodyBuilder) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.bodyBuilder = bodyBuilder;
    }

    @Override
    public String getProvider() {
        return "qwen";
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        String body = buildRequestBody(request, false);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(120))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return parseResponse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Qwen API 请求被中断", e);
        } catch (Exception e) {
            throw new RuntimeException("Qwen API 请求失败", e);
        }
    }

    @Override
    public Flux<LlmChunk> chatStream(LlmRequest request) {
        String body = buildRequestBody(request, true);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                // 流式请求不设 per-request timeout：推理模型的推理+生成过程常超 120s，
                // 固定 timeout 会截断流式传输导致回复不完整。
                // 连接建立由 HttpClient.connectTimeout(30s) 兜底，
                // 整体时长由 Spring MVC async request-timeout 兜底。
                .build();

        return Flux.<LlmChunk>create(sink -> {
            try {
                HttpResponse<java.io.InputStream> response = httpClient.send(httpRequest,
                        HttpResponse.BodyHandlers.ofInputStream());
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(response.body()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        if ("[DONE]".equals(data)) {
                            break;
                        }
                        LlmChunk chunk = parseStreamChunk(data);
                        if (chunk != null) {
                            sink.next(chunk);
                        }
                    }
                }
                sink.complete();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sink.error(e);
            } catch (Exception e) {
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());  // 阻塞 I/O 必须调度到 boundedElastic，避免阻塞容器线程
    }

    private String buildRequestBody(LlmRequest request, boolean stream) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode input = objectMapper.createObjectNode();
        input.set("messages", bodyBuilder.buildMessagesArray(request.getMessages()));
        root.set("input", input);

        if (request.getModel() != null) {
            root.put("model", request.getModel());
        }

        ObjectNode parameters = objectMapper.createObjectNode();
        if (request.getTemperature() != null) {
            parameters.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            parameters.put("max_tokens", request.getMaxTokens());
        }
        parameters.put("incremental_output", stream);
        if (stream) {
            parameters.put("result_format", "message");
        }
        root.set("parameters", parameters);

        if (request.getTools() != null && !request.getTools().isEmpty()) {
            root.set("tools", bodyBuilder.buildToolsArray(request.getTools()));
        }

        return bodyBuilder.serializeObject(root);
    }

    private LlmResponse parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode output = root.path("output");
            JsonNode choices = output.path("choices");

            LlmResponse.LlmResponseBuilder builder = LlmResponse.builder();

            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode firstChoice = choices.get(0);
                JsonNode message = firstChoice.path("message");

                String content = message.path("content").asText(null);
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
                        .promptTokens(usage.path("input_tokens").asInt(0))
                        .completionTokens(usage.path("output_tokens").asInt(0))
                        .totalTokens(usage.path("total_tokens").asInt(0))
                        .build());
            }

            return builder.build();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("解析 Qwen 响应失败", e);
        }
    }

    private LlmChunk parseStreamChunk(String data) {
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode output = root.path("output");

            LlmChunk.LlmChunkBuilder builder = LlmChunk.builder();

            String text = output.path("text").asText(null);
            if (text != null && !text.isEmpty()) {
                builder.delta(text);
            }

            JsonNode choices = output.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode firstChoice = choices.get(0);
                JsonNode delta = firstChoice.path("delta");

                String content = delta.path("content").asText(null);
                if (content != null && !content.isEmpty()) {
                    builder.delta(content);
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
                if (finishReason != null) {
                    builder.finishReason(finishReason);
                }
            }

            return builder.build();
        } catch (JsonProcessingException e) {
            log.warn("解析 Qwen 流式 chunk 失败: {}", data, e);
            return null;
        }
    }
}
