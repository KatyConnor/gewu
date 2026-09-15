package com.gewu.interfaceapi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.application.agent.AgentExecutionEngine;
import com.gewu.application.agent.dto.AgentChunk;
import com.gewu.application.agent.dto.AgentExecutionRequest;
import com.gewu.application.ai.ModelConfigService;
import com.gewu.application.ai.dto.ChatRequest;
import com.gewu.application.ai.dto.ChatResponse;
import com.gewu.application.ai.dto.ChatStreamEvent;
import com.gewu.application.ai.dto.ModelConfigDTO;
import com.gewu.application.ai.dto.ModelInfo;
import com.gewu.application.ai.dto.ToolCallInfo;
import com.gewu.application.ai.dto.ToolResultInfo;
import com.gewu.application.ai.dto.UsageInfo;
import com.gewu.application.session.SessionContextService;
import com.gewu.application.wenshi.reasoning.WenshiReasoningChunk;
import com.gewu.application.wenshi.reasoning.WenshiReasoningEngine;
import com.gewu.application.wenshi.reasoning.WenshiReasoningRequest;
import com.gewu.application.wenshi.reasoning.WenshiReasoningResult;
import com.gewu.common.context.UserContext;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.Result;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.session.Session;
import com.gewu.domain.session.SessionMessage;
import com.gewu.infrastructure.llm.LlmResponse;
import com.gewu.infrastructure.mapper.SessionMapper;
import com.gewu.infrastructure.mapper.SessionMessageMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
@Tag(name = "AI 对话", description = "AI 聊天接口，支持同步与 SSE 流式响应")
public class AiChatController {

    private final AgentExecutionEngine agentExecutionEngine;
    private final SessionContextService sessionContextService;
    private final ModelConfigService modelConfigService;
    private final ObjectMapper objectMapper;
    private final SessionMessageMapper sessionMessageMapper;
    private final SessionMapper sessionMapper;

    @Autowired(required = false)
    private WenshiReasoningEngine wenshiReasoningEngine;

    public AiChatController(AgentExecutionEngine agentExecutionEngine,
                            SessionContextService sessionContextService,
                            ModelConfigService modelConfigService,
                            ObjectMapper objectMapper,
                            SessionMessageMapper sessionMessageMapper,
                            SessionMapper sessionMapper) {
        this.agentExecutionEngine = agentExecutionEngine;
        this.sessionContextService = sessionContextService;
        this.modelConfigService = modelConfigService;
        this.objectMapper = objectMapper;
        this.sessionMessageMapper = sessionMessageMapper;
        this.sessionMapper = sessionMapper;
    }

    @Value("${gewu.ai.qwen.api-key:}")
    private String qwenApiKey;

    @Value("${gewu.ai.deepseek.api-key:}")
    private String deepseekApiKey;

    @Value("${gewu.wenshi.routing.chat:legacy}")
    private String chatRouting;

    @Value("${gewu.wenshi.routing.stream:legacy}")
    private String streamRouting;

    @PostMapping("/chat")
    @Operation(summary = "同步对话", description = "发送消息并获取完整 AI 响应")
    public Result<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        // 引擎选择：请求级覆盖（基准评测）优先，否则按全局路由配置
        String engine = request.getEngineOverride() != null && !request.getEngineOverride().isBlank()
                ? request.getEngineOverride() : chatRouting;
        log.info("同步对话: agentId={}, sessionId={}, routing={}", request.getAgentId(), request.getSessionId(), engine);

        if ("wenshi".equals(engine) && wenshiReasoningEngine != null) {
            return Result.success(chatViaWenshi(request));
        }

        return Result.success(chatViaLegacy(request));
    }

    /**
     * 流式对话 - 使用 StreamingResponseBody 直接控制输出流，每个 SSE 事件后立即 flush。
     * <p>
     * 不使用 Flux&lt;T&gt; 返回类型，因为 Spring MVC 的 ReactiveTypeHandler 会缓冲所有事件，
     * 直到 Flux 完成后才一次性 flush，导致前端无法实时看到流式输出。
     * StreamingResponseBody 让我们直接操作 OutputStream，每个事件写入后调用 flush()，
     * 确保数据实时推送到客户端。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式对话", description = "发送消息并通过 SSE 接收流式 AI 响应")
    public StreamingResponseBody chatStream(@Valid @RequestBody ChatRequest request) {
        log.info("流式对话: agentId={}, sessionId={}, routing={}", request.getAgentId(), request.getSessionId(), streamRouting);

        String userId = UserContext.currentUserId();
        String userMessage = request.getMessage();
        String sessionId = request.getSessionId();
        AtomicReference<StringBuilder> accumulated = new AtomicReference<>(new StringBuilder());
        java.util.List<ChatStreamEvent.FileEventInfo> fileEvents = new java.util.ArrayList<>();

        // 构建事件 Flux（不在此处保存会话，保存逻辑移到 StreamingResponseBody 完成后）
        // 引擎选择：请求级覆盖（基准评测）优先，否则按全局路由配置
        String engine = request.getEngineOverride() != null && !request.getEngineOverride().isBlank()
                ? request.getEngineOverride() : streamRouting;

        Flux<ChatStreamEvent> baseFlux;
        if ("wenshi".equals(engine) && wenshiReasoningEngine != null) {
            baseFlux = chatStreamViaWenshi(request);
            // Wenshi 路径执行账本（S7 补盲区：此前不经 AgentExecutionEngine 无记录）
            String wenshiExecId = agentExecutionEngine.beginExecutionRecord(toAgentExecutionRequest(request));
            if (wenshiExecId != null) {
                StringBuilder wenshiContent = new StringBuilder();
                java.util.concurrent.atomic.AtomicReference<Throwable> wenshiError = new java.util.concurrent.atomic.AtomicReference<>();
                baseFlux = baseFlux
                        .doOnNext(e -> {
                            if ("content".equals(e.getType()) && e.getContent() != null) {
                                wenshiContent.append(e.getContent());
                            }
                        })
                        .doOnError(wenshiError::set)
                        .doOnTerminate(() -> agentExecutionEngine.endExecutionRecord(
                                wenshiExecId,
                                wenshiError.get() == null ? wenshiContent.toString() : null,
                                null,
                                wenshiError.get() != null ? String.valueOf(wenshiError.get().getMessage()) : null));
            }
        } else {
            baseFlux = chatStreamViaLegacy(request);
        }

        // 累积 content 和 file 事件用于会话持久化
        final java.util.concurrent.atomic.AtomicReference<String> planJsonRef =
                new java.util.concurrent.atomic.AtomicReference<>(null);
        final Flux<ChatStreamEvent> eventFlux = baseFlux.doOnNext(event -> {
            if ("content".equals(event.getType()) && event.getContent() != null) {
                accumulated.get().append(event.getContent());
            }
            // 截断重试重新生成：清空已累积的部分正文，落库以最终重试结果为准
            if ("content_reset".equals(event.getType())) {
                accumulated.set(new StringBuilder());
            }
            if ("file".equals(event.getType()) && event.getFile() != null) {
                fileEvents.add(event.getFile());
            }
            // 任务计划最终快照（S9 F5）：done 事件携带，落库供历史回放
            if ("done".equals(event.getType()) && event.getPlan() != null && !event.getPlan().isEmpty()) {
                try {
                    planJsonRef.set(objectMapper.writeValueAsString(
                            java.util.Map.of("title", event.getPlanTitle() != null ? event.getPlanTitle() : "",
                                    "steps", event.getPlan())));
                } catch (Exception e) {
                    log.debug("计划快照序列化失败: {}", e.getMessage());
                }
            }
        });

        return outputStream -> {
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8), false);
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            AtomicBoolean clientGone = new AtomicBoolean(false);
            AtomicReference<Disposable> subRef = new AtomicReference<>();

            subRef.set(eventFlux.subscribe(
                    event -> {
                        // 客户端断连后静默丢弃剩余事件（S8：写失败会置位断连标记）
                        if (clientGone.get()) {
                            return;
                        }
                        try {
                            writer.write("data: ");
                            writer.write(objectMapper.writeValueAsString(event));
                            writer.write("\n\n");
                            writer.flush();
                        } catch (Exception e) {
                            // 写失败即客户端断连（broken pipe）：置位断连标记并终止订阅，
                            // 释放被占住的异步线程与上游 LLM 资源（S8 修复，此前会
                            // 持续写入失败直至 10 分钟异步超时）
                            log.error("SSE 写入失败，终止订阅: {}", e.getMessage());
                            clientGone.set(true);
                            latch.countDown();
                            Disposable d = subRef.get();
                            if (d != null) {
                                d.dispose();
                            }
                        }
                    },
                    error -> {
                        if (clientGone.get()) {
                            return;
                        }
                        errorRef.set(error);
                        log.error("SSE 流错误: {}", error.getMessage());
                        try {
                            ChatStreamEvent errorEvent = ChatStreamEvent.builder()
                                    .type("error")
                                    .errorMessage(error.getMessage() != null ? error.getMessage() : "AI 处理失败")
                                    .build();
                            writer.write("data: ");
                            writer.write(objectMapper.writeValueAsString(errorEvent));
                            writer.write("\n\n");
                            writer.flush();
                        } catch (Exception ignored) {
                        }
                        latch.countDown();
                    },
                    () -> {
                        if (clientGone.get()) {
                            return;
                        }
                        try {
                            writer.write("data: [DONE]\n\n");
                            writer.flush();
                        } catch (Exception ignored) {
                        }
                        latch.countDown();
                    }
            ));

            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                clientGone.set(true);
            } finally {
                // 请求线程侧最终清理：无论正常完成/断连/中断都终止上游订阅
                Disposable d = subRef.get();
                if (d != null) {
                    d.dispose();
                }
                writer.close();
            }

            // 流完成后保存会话交互记录。
            // 异常中断但已产出实质内容时同样落库（追加中断标记），
            // 避免已消耗 token 的交互丢失；clientId 幂等兜底防重复落库。
            if (sessionId != null) {
                try {
                    String assistantContent = accumulated.get().toString();
                    if (errorRef.get() != null) {
                        if (assistantContent.isBlank()) {
                            return;
                        }
                        String reason = errorRef.get().getMessage() != null
                                ? errorRef.get().getMessage() : "流式响应中断";
                        assistantContent = assistantContent + "\n[异常中断: " + reason + "]";
                    }
                    // 将文件元信息以 HTML 注释嵌入 content 末尾，前端加载时解析恢复文件卡片
                    if (!fileEvents.isEmpty()) {
                        String filesJson = objectMapper.writeValueAsString(fileEvents);
                        assistantContent = assistantContent + "\n<!--FILES:" + filesJson + "-->";
                    }
                    // 任务计划快照同样以注释嵌入（S9 F5：前端加载时恢复任务流程卡片）
                    if (planJsonRef.get() != null) {
                        assistantContent = assistantContent + "\n<!--PLAN:" + planJsonRef.get() + "-->";
                    }
                    if (!assistantContent.isBlank()) {
                        sessionContextService.appendChatInteraction(
                                sessionId, userId, userMessage, assistantContent, request.getClientId());
                    }
                } catch (Exception e) {
                    log.error("保存会话交互记录失败: sessionId={}", sessionId, e);
                }
            }
        };
    }

    @GetMapping("/models")
    @Operation(summary = "模型列表", description = "获取可用的 AI 模型列表（从数据库读取）")
    public Result<List<ModelInfo>> listModels() {
        List<ModelConfigDTO> activeModels = modelConfigService.listActiveModels();
        List<ModelInfo> models = activeModels.stream()
                .map(m -> ModelInfo.builder()
                        .provider(m.getProviderCode())
                        .name(m.getModelId())
                        .modelName(m.getModelId())
                        .displayName(m.getModelName())
                        .description(m.getDescription() != null ? m.getDescription() : "")
                        .supported(m.getStatus() != null && m.getStatus() == 1)
                        .build())
                .toList();
        return Result.success(models);
    }

    /**
     * 消息重新生成（SSE）- 以目标 AI 消息对应的原始用户输入重跑流式对话。
     * <p>语义：定位目标 assistant 消息前最近一条 user 消息，逻辑删除该 user 消息
     * （含）之后的全部消息，再以原内容与原会话 Agent 绑定重走 chatStream。
     */
    @PostMapping(value = "/sessions/{sessionId}/messages/{messageId}/regenerate",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "重新生成 AI 消息",
            description = "删除目标 AI 消息及其后的历史，以原始用户输入重新流式生成（SSE）")
    public StreamingResponseBody regenerateMessage(@PathVariable String sessionId,
                                                   @PathVariable String messageId) {
        SessionMessage target = sessionMessageMapper.selectById(messageId);
        if (target == null || !sessionId.equals(target.getSessionId())) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "消息不存在");
        }
        if (!"assistant".equals(target.getMessageType())) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "仅支持对 AI 消息重新生成");
        }
        // 定位目标前最近一条 user 消息
        SessionMessage userMsg = sessionMessageMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SessionMessage>()
                        .eq(SessionMessage::getSessionId, sessionId)
                        .eq(SessionMessage::getMessageType, "user")
                        .lt(SessionMessage::getSeq, target.getSeq())
                        .orderByDesc(SessionMessage::getSeq)
                        .last("LIMIT 1"));
        if (userMsg == null) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "未找到对应的用户消息，无法重新生成");
        }

        // 逻辑删除：原始 user 消息（含）之后的所有消息
        sessionMessageMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SessionMessage>()
                        .eq(SessionMessage::getSessionId, sessionId)
                        .ge(SessionMessage::getSeq, userMsg.getSeq()));

        // 以原输入重走流式（会话级 Agent 绑定回退保持一致）
        Session session = sessionMapper.selectById(sessionId);
        ChatRequest request = ChatRequest.builder()
                .sessionId(sessionId)
                .message(userMsg.getContent())
                .agentId(session != null ? session.getAgent() : null)
                .clientId(UUID.randomUUID().toString())
                .stream(true)
                .build();
        log.info("消息重新生成: sessionId={}, 删除自 seq={} 起, messageId={}",
                sessionId, userMsg.getSeq(), messageId);
        return chatStream(request);
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
        ToolResultInfo toolResultInfo = null;
        if (chunk.getToolResult() != null) {
            toolResultInfo = ToolResultInfo.builder()
                    .toolCallId(chunk.getToolResult().getToolCallId())
                    .output(chunk.getToolResult().getResult())
                    .build();
        }
        java.util.List<ChatStreamEvent.PlanStepInfo> plan = null;
        if (chunk.getPlan() != null) {
            plan = chunk.getPlan().stream()
                    .map(s -> ChatStreamEvent.PlanStepInfo.builder()
                            .id(s.getId()).text(s.getText()).status(s.getStatus()).build())
                    .toList();
        }
        return ChatStreamEvent.builder()
                .type(chunk.getType())
                .content(chunk.getContent())
                .reasoning(chunk.getReasoning())
                .toolCall(toolCallInfo)
                .toolResult(toolResultInfo)
                .errorMessage(chunk.getErrorMessage())
                .finishReason(chunk.getFinishReason())
                .planTitle(chunk.getPlanTitle())
                .plan(plan)
                .build();
    }

    /** ChatRequest -> AgentExecutionRequest（Wenshi 路径账本记录用，字段对齐 legacy 链路） */
    private AgentExecutionRequest toAgentExecutionRequest(ChatRequest request) {
        return AgentExecutionRequest.builder()
                .agentId(request.getAgentId())
                .sessionId(request.getSessionId())
                .message(request.getMessage())
                .model(request.getModel())
                .agentMode(request.getAgentMode())
                .thinkingStyle(request.getThinkingStyle())
                .modelRouteEnabled(request.getModelRouteEnabled())
                .build();
    }

    private ChatResponse chatViaWenshi(ChatRequest request) {
        String userId = UserContext.currentUserId();
        WenshiReasoningRequest wenshiRequest = WenshiReasoningRequest.builder()
                .agentId(request.getAgentId())
                .sessionId(request.getSessionId())
                .userId(userId)
                .tenantId(UserContext.currentTenantId())
                .message(request.getMessage())
                .model(request.getModel())
                .projectId(request.getProjectId())
                .requirementId(request.getRequirementId())
                .constraints(WenshiReasoningRequest.ReasoningConstraints.defaults())
                .build();

        String wenshiExecId = agentExecutionEngine.beginExecutionRecord(toAgentExecutionRequest(request));
        WenshiReasoningResult result = wenshiReasoningEngine.reason(wenshiRequest);

        if (request.getSessionId() != null) {
            sessionContextService.appendChatInteraction(
                    request.getSessionId(), userId, request.getMessage(), result.getAnswer(), request.getClientId());
        }
        agentExecutionEngine.endExecutionRecord(wenshiExecId, result.getAnswer(), null, null);

        return ChatResponse.builder()
                .messageId(java.util.UUID.randomUUID().toString())
                .content(result.getAnswer())
                .finishReason("stop")
                .build();
    }

    private ChatResponse chatViaLegacy(ChatRequest request) {
        AgentExecutionRequest executionRequest = AgentExecutionRequest.builder()
                .agentId(request.getAgentId())
                .sessionId(request.getSessionId())
                .message(request.getMessage())
                .model(request.getModel())
                .agentMode(request.getAgentMode())
                .thinkingStyle(request.getThinkingStyle())
                .build();
        LlmResponse llmResponse = agentExecutionEngine.executeAgent(executionRequest);

        if (request.getSessionId() != null) {
            String userId = UserContext.currentUserId();
            sessionContextService.appendChatInteraction(
                    request.getSessionId(), userId, request.getMessage(), llmResponse.getContent(), request.getClientId());
        }

        return toChatResponse(llmResponse);
    }

    private Flux<ChatStreamEvent> chatStreamViaWenshi(ChatRequest request) {
        String userId = UserContext.currentUserId();
        WenshiReasoningRequest wenshiRequest = WenshiReasoningRequest.builder()
                .agentId(request.getAgentId())
                .sessionId(request.getSessionId())
                .userId(userId)
                .tenantId(UserContext.currentTenantId())
                .message(request.getMessage())
                .model(request.getModel())
                .projectId(request.getProjectId())
                .requirementId(request.getRequirementId())
                .constraints(WenshiReasoningRequest.ReasoningConstraints.defaults())
                .build();

        return wenshiReasoningEngine.reasonStream(wenshiRequest)
                .map(chunk -> ChatStreamEvent.builder()
                        .type(chunk.getType())
                        .content(chunk.getContent())
                        .reasoning(chunk.getReasoning())
                        .toolCall(chunk.getToolCall() != null ? ToolCallInfo.builder()
                                .id(chunk.getToolCall().getId())
                                .name(chunk.getToolCall().getName())
                                .arguments(chunk.getToolCall().getArguments())
                                .build() : null)
                        .toolResult(chunk.getToolResult() != null ? ToolResultInfo.builder()
                                .toolCallId(chunk.getToolResult().getToolCallId())
                                .output(chunk.getToolResult().getResult())
                                .build() : null)
                        .webSearch(chunk.getWebSearch() != null ? toWebSearchEventInfo(chunk.getWebSearch()) : null)
                        .verify(chunk.getVerify() != null ? toVerifyEventInfo(chunk.getVerify()) : null)
                        .file(chunk.getFile() != null ? toFileEventInfo(chunk.getFile()) : null)
                        .errorMessage(chunk.getErrorMessage())
                        .build());
    }

    private Flux<ChatStreamEvent> chatStreamViaLegacy(ChatRequest request) {
        AgentExecutionRequest executionRequest = AgentExecutionRequest.builder()
                .agentId(request.getAgentId())
                .sessionId(request.getSessionId())
                .message(request.getMessage())
                .model(request.getModel())
                .agentMode(request.getAgentMode())
                .thinkingStyle(request.getThinkingStyle())
                .build();

        return agentExecutionEngine.executeAgentStream(executionRequest)
                .map(this::toStreamEvent);
    }

    /**
     * 将 WenshiReasoningChunk.WebSearchInfo 转换为 ChatStreamEvent.WebSearchEventInfo。
     */
    private ChatStreamEvent.WebSearchEventInfo toWebSearchEventInfo(
            WenshiReasoningChunk.WebSearchInfo info) {
        return ChatStreamEvent.WebSearchEventInfo.builder()
                .query(info.getQuery())
                .results(info.getResults() != null ? info.getResults().stream()
                        .map(item -> ChatStreamEvent.SearchItemInfo.builder()
                                .url(item.getUrl())
                                .title(item.getTitle())
                                .snippet(item.getSnippet())
                                .source(item.getSource())
                                .adopted(item.isAdopted())
                                .discarded(item.isDiscarded())
                                .discardReason(item.getDiscardReason())
                                .confidence(item.getConfidence())
                                .build())
                        .toList() : null)
                .adoptedCount(info.getAdoptedCount())
                .discardedCount(info.getDiscardedCount())
                .build();
    }

    /**
     * 将 WenshiReasoningChunk.VerifyInfo 转换为 ChatStreamEvent.VerifyEventInfo。
     */
    private ChatStreamEvent.VerifyEventInfo toVerifyEventInfo(WenshiReasoningChunk.VerifyInfo info) {
        return ChatStreamEvent.VerifyEventInfo.builder()
                .method(info.getMethod())
                .query(info.getQuery())
                .totalResults(info.getTotalResults())
                .adoptedCount(info.getAdoptedCount())
                .discardedCount(info.getDiscardedCount())
                .build();
    }

    /**
     * 将 WenshiReasoningChunk.FileInfo 转换为 ChatStreamEvent.FileEventInfo。
     */
    private ChatStreamEvent.FileEventInfo toFileEventInfo(WenshiReasoningChunk.FileInfo info) {
        return ChatStreamEvent.FileEventInfo.builder()
                .fileName(info.getFileName())
                .fileType(info.getFileType())
                .mimeType(info.getMimeType())
                .fileSize(info.getFileSize())
                .downloadUrl(info.getDownloadUrl())
                .previewContent(info.getPreviewContent())
                .source(info.getSource())
                .build();
    }
}
