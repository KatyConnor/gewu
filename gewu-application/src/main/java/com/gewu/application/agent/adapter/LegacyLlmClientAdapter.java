package com.gewu.application.agent.adapter;

import com.gewu.agent.engine.llm.LlmClient;
import com.gewu.agent.engine.llm.model.LlmChunk;
import com.gewu.agent.engine.llm.model.LlmRequest;
import com.gewu.agent.engine.llm.model.LlmResponse;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * LLM 客户端适配 - 包装现有 infrastructure 层 {@code LlmClient} 为框架 {@link LlmClient}。
 * <p>将 infrastructure.Message/LlmRequest 等模型转换为 engine 模型。
 * 当 {@code agent.engine.adapter.enabled=true} 时，
 * {@link LlmClientAdapterRegistration} 会将现有 qwen / deepseek 客户端包装注册到框架注册中心。
 *
 * @since 1.0.0
 */
@RequiredArgsConstructor
public class LegacyLlmClientAdapter implements LlmClient {

    private final com.gewu.infrastructure.llm.LlmClient delegate;

    @Override
    public String getProvider() {
        return delegate.getProvider();
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        com.gewu.infrastructure.llm.LlmRequest legacyReq = com.gewu.infrastructure.llm.LlmRequest.builder()
                .model(request.getModel())
                .messages(convertMessages(request.getMessages()))
                .tools(convertTools(request.getTools()))
                .temperature(request.getTemperature())
                .maxTokens(request.getMaxTokens())
                .stream(request.getStream())
                .build();
        com.gewu.infrastructure.llm.LlmResponse legacyResp = delegate.chat(legacyReq);
        return LlmResponse.builder()
                .content(legacyResp.getContent())
                .toolCalls(legacyResp.getToolCalls() != null
                        ? legacyResp.getToolCalls().stream()
                        .map(tc -> com.gewu.agent.engine.llm.model.ToolCall.builder()
                                .id(tc.getId()).name(tc.getName()).arguments(tc.getArguments())
                                .build())
                        .toList()
                        : null)
                .usage(legacyResp.getUsage() != null
                        ? LlmResponse.Usage.builder()
                        .promptTokens(legacyResp.getUsage().getPromptTokens())
                        .completionTokens(legacyResp.getUsage().getCompletionTokens())
                        .totalTokens(legacyResp.getUsage().getTotalTokens())
                        .build()
                        : null)
                .finishReason(legacyResp.getFinishReason())
                .build();
    }

    @Override
    public Flux<LlmChunk> chatStream(LlmRequest request) {
        com.gewu.infrastructure.llm.LlmRequest legacyReq = com.gewu.infrastructure.llm.LlmRequest.builder()
                .model(request.getModel())
                .messages(convertMessages(request.getMessages()))
                .tools(convertTools(request.getTools()))
                .temperature(request.getTemperature())
                .maxTokens(request.getMaxTokens())
                .stream(true)
                .build();
        return delegate.chatStream(legacyReq).map(legacy -> LlmChunk.builder()
                .delta(legacy.getDelta())
                .reasoning(legacy.getReasoning())
                .toolCallDelta(legacy.getToolCallDelta() != null
                        ? LlmChunk.ToolCallDelta.builder()
                        .id(legacy.getToolCallDelta().getId())
                        .name(legacy.getToolCallDelta().getName())
                        .arguments(legacy.getToolCallDelta().getArguments())
                        .build()
                        : null)
                .finishReason(legacy.getFinishReason())
                .build());
    }

    private List<com.gewu.infrastructure.llm.Message> convertMessages(List<com.gewu.agent.engine.llm.model.Message> messages) {
        if (messages == null) {
            return List.of();
        }
        return messages.stream()
                .map(m -> com.gewu.infrastructure.llm.Message.builder()
                        .role(m.getRole())
                        .content(m.getContent())
                        .toolCallId(m.getToolCallId())
                        .name(m.getName())
                        .build())
                .toList();
    }

    private List<com.gewu.infrastructure.llm.ToolDefinition> convertTools(
            List<com.gewu.agent.engine.llm.model.ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        return tools.stream()
                .map(t -> com.gewu.infrastructure.llm.ToolDefinition.builder()
                        .name(t.getName())
                        .description(t.getDescription())
                        .parameters(t.getParameters())
                        .build())
                .toList();
    }
}