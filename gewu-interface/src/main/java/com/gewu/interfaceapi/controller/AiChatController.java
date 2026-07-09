package com.gewu.interfaceapi.controller;

import com.gewu.application.agent.AgentExecutionEngine;
import com.gewu.application.agent.dto.AgentChunk;
import com.gewu.application.agent.dto.AgentExecutionRequest;
import com.gewu.application.ai.dto.ChatRequest;
import com.gewu.application.ai.dto.ChatResponse;
import com.gewu.application.ai.dto.ChatStreamEvent;
import com.gewu.application.ai.dto.ModelInfo;
import com.gewu.application.ai.dto.ToolCallInfo;
import com.gewu.application.ai.dto.UsageInfo;
import com.gewu.application.session.SessionContextService;
import com.gewu.common.result.Result;
import com.gewu.infrastructure.llm.LlmResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
@Tag(name = "AI 对话", description = "AI 聊天接口，支持同步与 SSE 流式响应")
public class AiChatController {

    private final AgentExecutionEngine agentExecutionEngine;
    private final SessionContextService sessionContextService;

    @PostMapping("/chat")
    @Operation(summary = "同步对话", description = "发送消息并获取完整 AI 响应")
    public Result<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        log.info("同步对话: agentId={}, sessionId={}", request.getAgentId(), request.getSessionId());
        AgentExecutionRequest executionRequest = AgentExecutionRequest.builder()
                .agentId(request.getAgentId())
                .sessionId(request.getSessionId())
                .message(request.getMessage())
                .build();
        LlmResponse llmResponse = agentExecutionEngine.executeAgent(executionRequest);
        return Result.success(toChatResponse(llmResponse));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式对话", description = "发送消息并通过 SSE 接收流式 AI 响应")
    public Flux<ChatStreamEvent> chatStream(@Valid @RequestBody ChatRequest request) {
        log.info("流式对话: agentId={}, sessionId={}", request.getAgentId(), request.getSessionId());
        AgentExecutionRequest executionRequest = AgentExecutionRequest.builder()
                .agentId(request.getAgentId())
                .sessionId(request.getSessionId())
                .message(request.getMessage())
                .build();
        return agentExecutionEngine.executeAgentStream(executionRequest)
                .map(this::toStreamEvent);
    }

    @GetMapping("/models")
    @Operation(summary = "模型列表", description = "获取可用的 AI 模型列表")
    public Result<List<ModelInfo>> listModels() {
        return Result.success(List.of(
                ModelInfo.builder().provider("openai").name("gpt-4o").displayName("GPT-4o").description("OpenAI GPT-4o").build(),
                ModelInfo.builder().provider("openai").name("gpt-4o-mini").displayName("GPT-4o Mini").description("OpenAI GPT-4o Mini").build(),
                ModelInfo.builder().provider("anthropic").name("claude-3.5-sonnet").displayName("Claude 3.5 Sonnet").description("Anthropic Claude 3.5 Sonnet").build()
        ));
    }

    private ChatResponse toChatResponse(LlmResponse response) {
        List<ToolCallInfo> toolCalls = null;
        if (response.getToolCalls() != null) {
            toolCalls = response.getToolCalls().stream()
                    .map(tc -> ToolCallInfo.builder()
                            .id(tc.getId())
                            .name(tc.getName())
                            .arguments(tc.getArguments())
                            .build())
                    .toList();
        }
        UsageInfo usage = null;
        if (response.getUsage() != null) {
            usage = UsageInfo.builder()
                    .promptTokens(response.getUsage().getPromptTokens())
                    .completionTokens(response.getUsage().getCompletionTokens())
                    .totalTokens(response.getUsage().getTotalTokens())
                    .build();
        }
        return ChatResponse.builder()
                .messageId(UUID.randomUUID().toString())
                .content(response.getContent())
                .toolCalls(toolCalls)
                .usage(usage)
                .finishReason(response.getFinishReason())
                .build();
    }

    private ChatStreamEvent toStreamEvent(AgentChunk chunk) {
        ToolCallInfo toolCallInfo = null;
        if (chunk.getToolCall() != null) {
            toolCallInfo = ToolCallInfo.builder()
                    .id(chunk.getToolCall().getId())
                    .name(chunk.getToolCall().getName())
                    .arguments(chunk.getToolCall().getArguments())
                    .build();
        }
        return ChatStreamEvent.builder()
                .type(chunk.getType())
                .content(chunk.getContent())
                .toolCall(toolCallInfo)
                .errorMessage(chunk.getErrorMessage())
                .build();
    }
}
