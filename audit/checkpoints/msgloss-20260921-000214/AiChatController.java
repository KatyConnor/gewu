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
import com.gewu.application.session.SessionFileWorkspaceService;

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
    private final SessionFileWorkspaceService fileWorkspaceService;

    @Autowired(required = false)
    private WenshiReasoningEngine wenshiReasoningEngine;

    public AiChatController(AgentExecutionEngine agentExecutionEngine,
                            SessionContextService sessionContextService,
                            ModelConfigService modelConfigService,
                            ObjectMapper objectMapper,
                            SessionMessageMapper sessionMessageMapper,
                            SessionMapper sessionMapper,
                            SessionFileWorkspaceService fileWorkspaceService) {
        this.agentExecutionEngine = agentExecutionEngine;
        this.sessionContextService = sessionContextService;
        this.modelConfigService = modelConfigService;
        this.objectMapper = objectMapper;
        this.sessionMessageMapper = sessionMessageMapper;
        this.sessionMapper = sessionMapper;
        this.fileWorkspaceService = fileWorkspaceService;
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
        // 过程时间线摘要（S9 问题3修复）：轻量累积供 metadata 持久化，
        // 前端历史消息据此还原折叠过程视图。条目截断控制体积。
        final java.util.List<java.util.Map<String, Object>> processSummary = new java.util.ArrayList<>();
        final long[] thinkStart = {0};
        final StringBuilder thinkSnippet = new StringBuilder();
        final java.util.Map<String, java.util.Map<String, Object>> openTools = new java.util.LinkedHashMap<>();
        final long streamStartMs = System.currentTimeMillis();
        // 回合快照（撤销功能）：本回合开始——后续文件写入将记录回合前内容，
        // 并标记回合进行中（期间拒绝撤销，防与引擎写入并发冲突）
        if (sessionId != null && !sessionId.isBlank()) {
            fileWorkspaceService.beginTurn(sessionId);
        }
        // 中间正文段（用户实报问题3）：正文按"被 thinking/tool_call 打断的位置"切段——
        // 中间段持久化进 metadata.process 保留在过程中；流结束时未闭合的当前段即
        // 最终正文，作为 assistantContent 主体（最终结果汇总在过程之后输出）
        final long[] contentSegStart = {0};
        final StringBuilder contentSegText = new StringBuilder();
        final StringBuilder contentSegSnippet = new StringBuilder();
        // 带内终止原因（用户实报问题3落库缺口）：流内 error / budget_exceeded 事件不是
        // Flux 错误（errorRef 不触发），此前无正文的报错整轮交互（含用户消息）不落库
        final java.util.concurrent.atomic.AtomicReference<String> stopReason =
                new java.util.concurrent.atomic.AtomicReference<>();
        // 执行统计（完成透明度）：done 事件携带 rounds/elapsedMs/tokenEstimated 等，
        // 落库 metadata.stats 供历史回放还原"AI 自主收尾 vs 被限制收尾"
        final java.util.concurrent.atomic.AtomicReference<java.util.Map<String, Object>> doneStatsRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        // 回合文件汇总（撤销功能）：流收尾时计算快照差值，持久化 metadata.turnFiles
        final java.util.concurrent.atomic.AtomicReference<java.util.List<SessionFileWorkspaceService.TurnFileChange>> turnFilesRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final Flux<ChatStreamEvent> eventFlux = baseFlux.doOnNext(event -> {
            if ("content".equals(event.getType()) && event.getContent() != null) {
                accumulated.get().append(event.getContent());
                // 当前正文段续写（无未闭合段则开新段）
                if (contentSegStart[0] == 0) {
                    contentSegStart[0] = System.currentTimeMillis();
                }
                contentSegText.append(event.getContent());
                if (contentSegSnippet.length() < 2000) {
                    int remain = 2000 - contentSegSnippet.length();
                    contentSegSnippet.append(event.getContent(), 0,
                            Math.min(event.getContent().length(), remain));
                }
            }
            // 截断重试重新生成：清空已累积的部分正文，落库以最终重试结果为准
            if ("content_reset".equals(event.getType())) {
                accumulated.set(new StringBuilder());
                // 未闭合的当前段整体作废（旧内容将被重新生成替换）
                contentSegStart[0] = 0;
                contentSegText.setLength(0);
                contentSegSnippet.setLength(0);
            }
            // 带内终止：error / budget_exceeded 事件记录原因（供无正文时也落库）
            if ("error".equals(event.getType())) {
                stopReason.compareAndSet(null,
                        event.getErrorMessage() != null ? event.getErrorMessage() : "AI 处理失败");
            }
            if ("budget_exceeded".equals(event.getType())) {
                stopReason.compareAndSet(null,
                        event.getErrorMessage() != null ? event.getErrorMessage() : "任务预算已用尽");
            }
            // 优雅降级收尾（用户实报：轮次/循环总结路径此前按正常完成落库，历史无痕）：
            // done 事件的 finishReason 计入 stopReason，落库追加 [执行中断] 标记
            if ("done".equals(event.getType()) && event.getFinishReason() != null) {
                switch (event.getFinishReason()) {
                    case "rounds" -> stopReason.compareAndSet(null, "工具调用轮次上限");
                    case "loop" -> stopReason.compareAndSet(null, "重复执行循环");
                    case "budget" -> stopReason.compareAndSet(null, "任务预算已用尽");
                    default -> { /* stop/length 等正常完成原因不标记 */ }
                }
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
            // 执行统计快照（完成透明度）：done 事件的 metadata 持久化
            if ("done".equals(event.getType()) && event.getMetadata() != null) {
                doneStatsRef.set(event.getMetadata());
            }
            // 过程时间线摘要累积（S9 问题3修复）：thinking 段/工具调用轻量记录
            long nowMs = System.currentTimeMillis();
            // 中间正文段闭合（用户实报问题3）：thinking/tool_call 打断正文 → 当前段
            // 进过程时间线（保留在过程中原位置），最终正文留给流结束时的未闭合段
            if (("thinking".equals(event.getType()) && event.getReasoning() != null)
                    || "tool_call".equals(event.getType())) {
                if (contentSegStart[0] > 0) {
                    String segText = contentSegSnippet.toString();
                    if (!segText.isBlank() && processSummary.size() < 100) {
                        java.util.Map<String, Object> seg = new java.util.LinkedHashMap<>();
                        seg.put("k", "content");
                        seg.put("s", contentSegStart[0] - streamStartMs);
                        seg.put("e", nowMs - streamStartMs);
                        seg.put("t", segText);
                        processSummary.add(seg);
                    }
                    contentSegStart[0] = 0;
                    contentSegText.setLength(0);
                    contentSegSnippet.setLength(0);
                }
            }
            if ("thinking".equals(event.getType()) && event.getReasoning() != null) {
                if (thinkStart[0] == 0) {
                    thinkStart[0] = nowMs;
                    thinkSnippet.setLength(0);
                }
                if (thinkSnippet.length() < 500) {
                    thinkSnippet.append(event.getReasoning());
                }
            } else if ("content".equals(event.getType()) || "tool_call".equals(event.getType())
                    || "budget_exceeded".equals(event.getType()) || "error".equals(event.getType())) {
                // 思考段被正文/工具打断：闭合思考段
                if (thinkStart[0] > 0) {
                    java.util.Map<String, Object> seg = new java.util.LinkedHashMap<>();
                    seg.put("k", "thinking");
                    seg.put("s", thinkStart[0] - streamStartMs);
                    seg.put("e", nowMs - streamStartMs);
                    seg.put("t", thinkSnippet.toString());
                    if (processSummary.size() < 100) {
                        processSummary.add(seg);
                    }
                    thinkStart[0] = 0;
                }
            }
            if ("tool_call".equals(event.getType()) && event.getToolCall() != null && processSummary.size() < 100) {
                java.util.Map<String, Object> tool = new java.util.LinkedHashMap<>();
                tool.put("k", "tool");
                tool.put("s", nowMs - streamStartMs);
                tool.put("n", event.getToolCall().getName());
                String args = event.getToolCall().getArguments();
                // 编辑类工具保留大参数（old_text/new_text/content 全文）：历史会话回放时前端
                // 据此渲染行级差异块；其余工具维持 200 字符摘要，防 metadata 无界膨胀
                String summaryToolName = event.getToolCall().getName();
                int argsCap = "edit_file".equals(summaryToolName) || "write_file".equals(summaryToolName)
                        ? 20000 : 200;
                tool.put("a", args != null && args.length() > argsCap ? args.substring(0, argsCap) : args);
                tool.put("id", event.getToolCall().getId());
                processSummary.add(tool);
                openTools.put(event.getToolCall().getId(), tool);
            }
            if ("tool_result".equals(event.getType()) && event.getToolResult() != null) {
                java.util.Map<String, Object> tool = openTools.remove(event.getToolResult().getToolCallId());
                if (tool != null) {
                    tool.put("e", nowMs - streamStartMs);
                    String output = event.getToolResult().getOutput();
                    tool.put("r", output != null && output.length() > 200 ? output.substring(0, 200) : output);
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
                            synchronized (writer) {
                                writer.write("data: ");
                                writer.write(objectMapper.writeValueAsString(event));
                                writer.write("\n\n");
                                writer.flush();
                            }
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
                            synchronized (writer) {
                                writer.write("data: ");
                                writer.write(objectMapper.writeValueAsString(errorEvent));
                                writer.write("\n\n");
                                writer.flush();
                            }
                        } catch (Exception ignored) {
                        }
                        latch.countDown();
                    },
                    () -> {
                        if (clientGone.get()) {
                            return;
                        }
                        try {
                            synchronized (writer) {
                                writer.write("data: [DONE]\n\n");
                                writer.flush();
                            }
                        } catch (Exception ignored) {
                        }
                        latch.countDown();
                    }
            ));

            // 心跳保活（S9）：工具执行长静默期（单轮工具可能数十秒无事件）每 20s
            // 发 ping 帧，防中间层空闲超时掐断连接；前端对未知事件类型自动忽略。
            // 与订阅写入共用 writer（同步块互斥），订阅终止即停。
            java.util.concurrent.ScheduledExecutorService heartbeatExecutor =
                    java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "sse-heartbeat");
                        t.setDaemon(true);
                        return t;
                    });
            java.util.concurrent.ScheduledFuture<?> heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(() -> {
                if (clientGone.get()) {
                    return;
                }
                try {
                    synchronized (writer) {
                        writer.write("data: {\"type\":\"ping\"}\n\n");
                        writer.flush();
                    }
                } catch (Exception e) {
                    // 心跳写失败=连接已断：置位断连并终止上游订阅
                    clientGone.set(true);
                    latch.countDown();
                    Disposable d = subRef.get();
                    if (d != null) {
                        d.dispose();
                    }
                }
            }, 20, 20, java.util.concurrent.TimeUnit.SECONDS);

            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                clientGone.set(true);
            } finally {
                // 请求线程侧最终清理：无论正常完成/断连/中断都终止上游订阅与心跳
                heartbeatTask.cancel(false);
                heartbeatExecutor.shutdownNow();
                Disposable d = subRef.get();
                if (d != null) {
                    d.dispose();
                }
                writer.close();
                // 回合收尾（撤销功能）：先计算快照汇总（供 metadata.turnFiles 持久化），
                // 再解除「回合进行中」标记（此后允许撤销，快照保留至下一回合）
                if (sessionId != null && !sessionId.isBlank()) {
                    try {
                        turnFilesRef.set(fileWorkspaceService.turnChanges(sessionId));
                    } catch (Exception e) {
                        log.debug("回合文件汇总计算失败: {}", e.getMessage());
                    } finally {
                        fileWorkspaceService.finishTurn(sessionId);
                    }
                }
            }

            // 流完成后保存会话交互记录。
            // 异常中断但已产出实质内容时同样落库（追加中断标记），
            // 避免已消耗 token 的交互丢失；clientId 幂等兜底防重复落库。
            if (sessionId != null) {
                try {
                    // 落库拆分（用户实报问题3）：未闭合的当前正文段 = 最终正文（最后一轮
                    // 的执行结果/汇总）；中间轮次叙述已进 metadata.process 保留在过程中。
                    // 退化场景（最终轮无正文但此前有中间叙述）回退全量拼接防丢数据
                    String assistantContent = contentSegText.toString();
                    if (assistantContent.isBlank() && !accumulated.get().toString().isBlank()) {
                        assistantContent = accumulated.get().toString();
                    }
                    if (errorRef.get() != null) {
                        if (assistantContent.isBlank()) {
                            return;
                        }
                        String reason = errorRef.get().getMessage() != null
                                ? errorRef.get().getMessage() : "流式响应中断";
                        assistantContent = assistantContent + "\n[异常中断: " + reason + "]";
                    }
                    // 带内终止（轮次超限/预算熔断）：无正文也落库——用户消息与过程
                    // 时间线一并保存，报错消息切回会话可完整还原（此前整轮丢弃）；
                    // 有部分正文时追加中断标记明示不完整
                    if (stopReason.get() != null) {
                        if (assistantContent.isBlank()) {
                            assistantContent = "[执行中断: " + stopReason.get() + "]";
                        } else {
                            assistantContent = assistantContent + "\n[执行中断: " + stopReason.get() + "]";
                        }
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
                    // 过程时间线摘要写入 metadata（S9 问题3修复：前端历史还原折叠过程视图）
                    // processMs（用户实报：重进会话后时长显示"几秒"）：流式全程耗时，
                    // 前端历史重放时还原时间线头部的"已工作 X 分 X 秒"
                    String assistantMetadata = null;
                    boolean hasTurnFiles = turnFilesRef.get() != null && !turnFilesRef.get().isEmpty();
                    if (!processSummary.isEmpty() || doneStatsRef.get() != null || hasTurnFiles) {
                        try {
                            java.util.Map<String, Object> metadataMap = new java.util.LinkedHashMap<>();
                            if (!processSummary.isEmpty()) {
                                metadataMap.put("process", processSummary);
                            }
                            metadataMap.put("processMs", System.currentTimeMillis() - streamStartMs);
                            if (doneStatsRef.get() != null) {
                                metadataMap.put("stats", doneStatsRef.get());
                            }
                            // 回合文件汇总（撤销功能）：仅 path+增删行数，历史回放渲染汇总条
                            if (hasTurnFiles) {
                                metadataMap.put("turnFiles", turnFilesRef.get());
                            }
                            assistantMetadata = objectMapper.writeValueAsString(metadataMap);
                        } catch (Exception e) {
                            log.debug("过程摘要序列化失败: {}", e.getMessage());
                        }
                    }
                    if (!assistantContent.isBlank()) {
                        sessionContextService.appendChatInteraction(
                                sessionId, userId, userMessage, assistantContent, request.getClientId(),
                                assistantMetadata);
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
                .metadata(chunk.getMetadata())
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
