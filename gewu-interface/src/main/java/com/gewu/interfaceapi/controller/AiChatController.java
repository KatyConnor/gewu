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
import com.gewu.application.session.ChatRunRegistry;
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
    private final ChatRunRegistry chatRunRegistry;

    @Autowired(required = false)
    private WenshiReasoningEngine wenshiReasoningEngine;

    public AiChatController(AgentExecutionEngine agentExecutionEngine,
                            SessionContextService sessionContextService,
                            ModelConfigService modelConfigService,
                            ObjectMapper objectMapper,
                            SessionMessageMapper sessionMessageMapper,
                            SessionMapper sessionMapper,
                            SessionFileWorkspaceService fileWorkspaceService,
                            ChatRunRegistry chatRunRegistry) {
        this.agentExecutionEngine = agentExecutionEngine;
        this.sessionContextService = sessionContextService;
        this.modelConfigService = modelConfigService;
        this.objectMapper = objectMapper;
        this.sessionMessageMapper = sessionMessageMapper;
        this.sessionMapper = sessionMapper;
        this.fileWorkspaceService = fileWorkspaceService;
        this.chatRunRegistry = chatRunRegistry;
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
        // 同会话并发防护与用户消息即时落库放在 Flux 组装之后（见 return 前的守卫块）：
        // 组装仅构建冷 Flux 无副作用，注册一旦完成就必须保证终结——若在组装前注册，
        // 组装段异常会绕过 doFinally 留下僵尸 RUNNING，阻塞该会话后续所有轮次
        boolean hasSession = sessionId != null && !sessionId.isBlank();
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
        // 二期：CopyOnWriteArrayList——run 活跃期间经 ChatRunRegistry 引用被
        // 重进会话的轮询线程并发读取（GET run/process），写少读多场景安全
        final java.util.List<java.util.Map<String, Object>> processSummary =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        // 活动 run 的实时过程快照（重进会话可见执行中任务的时间线）：事件线程上构建
        // 不可变快照（processSummary + 未闭合的思考/正文段），轮询线程仅读引用，免并发读写
        final java.util.concurrent.atomic.AtomicReference<java.util.List<java.util.Map<String, Object>>> liveSnapshotRef =
                new java.util.concurrent.atomic.AtomicReference<>(new java.util.ArrayList<>());
        final long[] thinkStart = {0};
        final StringBuilder thinkSnippet = new StringBuilder();
        final java.util.Map<String, java.util.Map<String, Object>> openTools = new java.util.LinkedHashMap<>();
        final long streamStartMs = System.currentTimeMillis();
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
        // Flux 级异常捕获（断连不中断修复）：持久化迁入 doFinally 后不再依赖
        // 请求线程侧的 errorRef，订阅错误在此捕获供终结时落库中断标记
        final java.util.concurrent.atomic.AtomicReference<Throwable> fluxError =
                new java.util.concurrent.atomic.AtomicReference<>();
        // 单轮流式回合的累积上下文：供 doFinally 终结回调做落库（与请求线程解耦）
        final TurnContext turnCtx = new TurnContext(sessionId, userId, userMessage,
                request.getClientId(), accumulated, contentSegText, fileEvents, planJsonRef,
                processSummary, doneStatsRef, turnFilesRef, stopReason, fluxError, streamStartMs);
        final Flux<ChatStreamEvent> eventFlux = baseFlux.doOnNext(event -> {
            // 二期：子代理分支事件（带 subagentId 标签）不计入主轮次累积/正文/统计——
            // 分支过程由前端按 subagentId 分桶实时渲染；仅 subagent_status 生命周期行进摘要
            if (event.getMetadata() != null && event.getMetadata().containsKey("subagentId")
                    && !"subagent_status".equals(event.getType())) {
                return;
            }
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
            // 任务计划最终快照（S9 F5）：done 事件携带，落库供历史回放（planPath 供"查看完整计划"）
            if ("done".equals(event.getType()) && event.getPlan() != null && !event.getPlan().isEmpty()) {
                try {
                    java.util.Map<String, Object> planSnapshot = new java.util.LinkedHashMap<>();
                    planSnapshot.put("title", event.getPlanTitle() != null ? event.getPlanTitle() : "");
                    planSnapshot.put("steps", event.getPlan());
                    if (event.getMetadata() != null && event.getMetadata().get("planPath") != null) {
                        planSnapshot.put("planPath", String.valueOf(event.getMetadata().get("planPath")));
                    }
                    planJsonRef.set(objectMapper.writeValueAsString(planSnapshot));
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
            // HITL 问答条目（ask_user）：问题+选项进过程时间线（回答经随后的 ask_user 工具结果行可见）
            if ("ask_user".equals(event.getType()) && event.getMetadata() != null && processSummary.size() < 100) {
                java.util.Map<String, Object> ask = new java.util.LinkedHashMap<>();
                ask.put("k", "ask");
                ask.put("s", nowMs - streamStartMs);
                Object q = event.getMetadata().get("question");
                ask.put("q", q != null ? String.valueOf(q) : "");
                Object opts = event.getMetadata().get("options");
                ask.put("o", opts instanceof java.util.List ? opts : java.util.List.of());
                processSummary.add(ask);
            }
            // 子代理生命周期条目（spawn_subagents）：仅持久化完成态（running 行为实时态，回放无需）
            // 分支结果文本（截断 2000）随行持久化，供历史回放"点击子智能体→右侧面板展示聚合结果"
            if ("subagent_status".equals(event.getType()) && event.getMetadata() != null
                    && !"running".equals(String.valueOf(event.getMetadata().get("status")))
                    && processSummary.size() < 100) {
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("k", "subagent");
                Object sid = event.getMetadata().get("subagentId");
                row.put("id", sid != null ? String.valueOf(sid) : "");
                Object n = event.getMetadata().get("name");
                row.put("n", n != null ? String.valueOf(n) : "");
                Object st = event.getMetadata().get("status");
                row.put("st", st != null ? String.valueOf(st) : "success");
                row.put("s", nowMs - streamStartMs);
                row.put("e", nowMs - streamStartMs);
                Object res = event.getMetadata().get("result");
                row.put("r", res != null ? String.valueOf(res) : "");
                processSummary.add(row);
            }
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
            // 二期：重建活动 run 的实时过程快照——processSummary（已闭合段）+ 进行中的
            // 未闭合思考/正文段。未闭合段不写 e（结束偏移）：前端据 e 缺失渲染 active/
            // 流式态（旋转、增长预览、光标），与本地流式视图观感一致；历史回放（metadata
            // .process）恒有 e，口径兼容
            java.util.List<java.util.Map<String, Object>> liveSnap =
                    new java.util.ArrayList<>(processSummary);
            if (thinkStart[0] > 0) {
                java.util.Map<String, Object> liveThink = new java.util.LinkedHashMap<>();
                liveThink.put("k", "thinking");
                liveThink.put("s", thinkStart[0] - streamStartMs);
                // 未闭合段不写 e：前端渲染为 active 态（旋转 + 尾部增长预览）
                liveThink.put("t", thinkSnippet.toString());
                liveSnap.add(liveThink);
            }
            if (contentSegStart[0] > 0) {
                java.util.Map<String, Object> liveContent = new java.util.LinkedHashMap<>();
                liveContent.put("k", "content");
                liveContent.put("s", contentSegStart[0] - streamStartMs);
                // 未闭合段不写 e：前端对最后一段正文渲染流式光标
                liveContent.put("t", contentSegText.toString());
                liveSnap.add(liveContent);
            }
            liveSnapshotRef.set(liveSnap);
        })
                // 断连不中断修复（核心）：持久化从请求线程 post-latch 块迁入订阅终结回调。
                // 订阅生命周期与 HTTP 请求解耦——客户端断连后任务继续执行到自然结束，
                // 完整落库；落库幂等由 clientId("xxx"/"xxx:ai") 保证
                .doOnError(fluxError::set)
                .doFinally(signal -> {
                    try {
                        persistTurn(turnCtx);
                    } finally {
                        chatRunRegistry.finish(sessionId, fluxError.get() != null
                                ? ChatRunRegistry.Status.FAILED : ChatRunRegistry.Status.DONE);
                        // 移除实时过程引用（此后由消息 metadata 回放）
                        chatRunRegistry.removeLiveProcess(sessionId);
                    }
                });

        // 订阅前最后一步：注册运行 + 用户消息即时落库 + 回合开始。
        // 此后正常路径由 doFinally 终结注册；本守卫块内的异常路径由 catch 终结——
        // 两条路径覆盖全部出口，防止僵尸 RUNNING 阻塞该会话后续轮次。
        // 1) 同会话并发防护：上一轮仍在后台执行时拒绝新轮次（交错执行会导致
        //    上下文与落库顺序混乱）
        // 2) 用户消息即时落库（断连不中断修复）：此后无论断连/异常/进程重启，
        //    用户输入都不再丢失
        if (hasSession) {
            boolean registered = false;
            try {
                if (!chatRunRegistry.tryRegister(sessionId, request.getClientId())) {
                    throw BusinessException.of(ResultCode.SESSION_RUN_IN_PROGRESS);
                }
                registered = true;
                sessionContextService.appendUserMessage(sessionId, userId, userMessage, request.getClientId());
                // 回合快照（撤销功能）：本回合开始——后续文件写入将记录回合前内容，
                // 并标记回合进行中（期间拒绝撤销，防与引擎写入并发冲突）
                fileWorkspaceService.beginTurn(sessionId);
                // 注册实时过程引用（重进会话可见执行中任务的时间线）：doFinally 移除；
                // supplier 返回事件线程上构建的不可变快照（含未闭合段）
                chatRunRegistry.putLiveProcess(sessionId, liveSnapshotRef::get);
            } catch (RuntimeException e) {
                // 仅终结本次已注册的运行；并发拒绝路径未注册，绝不能误杀正在
                // 运行的其他轮次的注册项（否则其注册状态被提前置 FAILED）
                if (registered) {
                    chatRunRegistry.finish(sessionId, ChatRunRegistry.Status.FAILED);
                }
                throw e;
            }
        }

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
                                // PrintWriter 吞掉 IOException（仅置内部 error 标志）：
                                // broken pipe 不以异常抛出，必须用 checkError 显式检出断连
                                if (writer.checkError()) {
                                    throw new java.io.IOException("客户端已断开（checkError 检出写入失败）");
                                }
                            }
                        } catch (Exception e) {
                            // 写失败即客户端断连（broken pipe）：置位断连标记并释放请求线程。
                            // 不再 dispose 订阅（断连不中断修复）：任务转后台继续执行到完成，
                            // 落库由 doFinally 负责；此后事件在消费入口静默丢弃
                            log.error("SSE 写入失败，客户端断连，任务转后台继续执行: {}", e.getMessage());
                            clientGone.set(true);
                            latch.countDown();
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
                        // 同消费端：PrintWriter 吞 IOException，checkError 检出断连
                        if (writer.checkError()) {
                            throw new java.io.IOException("客户端已断开（心跳 checkError 检出）");
                        }
                    }
                } catch (Exception e) {
                    // 心跳写失败=连接已断：置位断连并释放请求线程（订阅继续，任务转后台）
                    clientGone.set(true);
                    latch.countDown();
                }
            }, 20, 20, java.util.concurrent.TimeUnit.SECONDS);

            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                clientGone.set(true);
            } finally {
                // 请求线程侧清理：仅停心跳、关 writer。不 dispose 订阅——
                // 断连/中断后任务继续后台执行到自然结束，落库由 doFinally 负责
                heartbeatTask.cancel(false);
                heartbeatExecutor.shutdownNow();
                writer.close();
            }
        };
    }

    /**
     * 单轮流式回合的累积上下文（doFinally 终结持久化入参）。
     * <p>record 字段与 chatStream 内局部累积器一一对应，避免超长参数列表。
     */
    private record TurnContext(
            String sessionId,
            String userId,
            String userMessage,
            String clientId,
            AtomicReference<StringBuilder> accumulated,
            StringBuilder contentSegText,
            java.util.List<ChatStreamEvent.FileEventInfo> fileEvents,
            AtomicReference<String> planJsonRef,
            java.util.List<java.util.Map<String, Object>> processSummary,
            AtomicReference<java.util.Map<String, Object>> doneStatsRef,
            AtomicReference<java.util.List<SessionFileWorkspaceService.TurnFileChange>> turnFilesRef,
            AtomicReference<String> stopReason,
            AtomicReference<Throwable> fluxError,
            long streamStartMs) {
    }

    /**
     * 回合终结持久化（断连不中断修复核心）：由订阅 doFinally 回调执行，
     * 与 HTTP 请求线程生命周期解耦——客户端断连后任务跑完仍会走到这里。
     * <p>闸门从"正文非空"放宽为"正文/过程/文件/计划任一非空"，杜绝整轮丢弃：
     * 无正文的带内终止/异常以中断标记占位，用户消息已在请求入口即时落库，
     * 此处只补齐助手消息与 metadata（过程时间线/统计/回合文件）。
     */
    private void persistTurn(TurnContext ctx) {
        String sessionId = ctx.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            // 回合收尾（撤销功能）：先计算快照汇总（供 metadata.turnFiles 持久化），
            // 再解除「回合进行中」标记（此后允许撤销，快照保留至下一回合）
            try {
                ctx.turnFilesRef().set(fileWorkspaceService.turnChanges(sessionId));
            } catch (Exception e) {
                log.debug("回合文件汇总计算失败: {}", e.getMessage());
            } finally {
                fileWorkspaceService.finishTurn(sessionId);
            }

            // 落库拆分（用户实报问题3）：未闭合的当前正文段 = 最终正文（最后一轮
            // 的执行结果/汇总）；中间轮次叙述已进 metadata.process 保留在过程中。
            // 退化场景（最终轮无正文但此前有中间叙述）回退全量拼接防丢数据
            String assistantContent = ctx.contentSegText().toString();
            if (assistantContent.isBlank() && !ctx.accumulated().get().toString().isBlank()) {
                assistantContent = ctx.accumulated().get().toString();
            }
            Throwable error = ctx.fluxError().get();
            String stopReason = ctx.stopReason().get();
            if (error != null) {
                log.warn("流式任务异常终结，按中断落库: sessionId={}, error={}",
                        sessionId, error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName());
                String reason = error.getMessage() != null ? error.getMessage() : "流式响应中断";
                assistantContent = assistantContent.isBlank()
                        ? "[异常中断: " + reason + "]"
                        : assistantContent + "\n[异常中断: " + reason + "]";
            } else if (stopReason != null) {
                // 带内终止（轮次超限/预算熔断）：无正文也落库——过程时间线一并保存，
                // 报错消息切回会话可完整还原
                assistantContent = assistantContent.isBlank()
                        ? "[执行中断: " + stopReason + "]"
                        : assistantContent + "\n[执行中断: " + stopReason + "]";
            }
            // 将文件元信息以 HTML 注释嵌入 content 末尾，前端加载时解析恢复文件卡片
            if (!ctx.fileEvents().isEmpty()) {
                String filesJson = objectMapper.writeValueAsString(ctx.fileEvents());
                assistantContent = assistantContent + "\n<!--FILES:" + filesJson + "-->";
            }
            // 任务计划快照同样以注释嵌入（S9 F5：前端加载时恢复任务流程卡片）
            if (ctx.planJsonRef().get() != null) {
                assistantContent = assistantContent + "\n<!--PLAN:" + ctx.planJsonRef().get() + "-->";
            }
            // 过程时间线摘要写入 metadata（S9 问题3修复）：前端历史还原折叠过程视图；
            // processMs（用户实报：重进会话后时长显示"几秒"）：流式全程耗时
            String assistantMetadata = null;
            boolean hasTurnFiles = ctx.turnFilesRef().get() != null && !ctx.turnFilesRef().get().isEmpty();
            if (!ctx.processSummary().isEmpty() || ctx.doneStatsRef().get() != null || hasTurnFiles) {
                try {
                    java.util.Map<String, Object> metadataMap = new java.util.LinkedHashMap<>();
                    if (!ctx.processSummary().isEmpty()) {
                        metadataMap.put("process", ctx.processSummary());
                    }
                    metadataMap.put("processMs", System.currentTimeMillis() - ctx.streamStartMs());
                    if (ctx.doneStatsRef().get() != null) {
                        metadataMap.put("stats", ctx.doneStatsRef().get());
                    }
                    // 回合文件汇总（撤销功能）：仅 path+增删行数，历史回放渲染汇总条
                    if (hasTurnFiles) {
                        metadataMap.put("turnFiles", ctx.turnFilesRef().get());
                    }
                    assistantMetadata = objectMapper.writeValueAsString(metadataMap);
                } catch (Exception e) {
                    log.debug("过程摘要序列化失败: {}", e.getMessage());
                }
            }
            // 落库闸门：正文为空但过程/文件/计划任一存在时也落库（占位文本承载体），
            // 防止"整轮交互只有用户消息、AI 侧无痕"
            boolean hasPayload = !assistantContent.isBlank() || !ctx.processSummary().isEmpty()
                    || hasTurnFiles || ctx.planJsonRef().get() != null;
            if (hasPayload && assistantContent.isBlank()) {
                assistantContent = "[本轮无文本输出，过程见时间线]";
            }
            if (!assistantContent.isBlank()) {
                sessionContextService.appendAssistantMessage(
                        sessionId, assistantContent, assistantMetadata, ctx.clientId());
            }
        } catch (Exception e) {
            log.error("保存会话交互记录失败: sessionId={}", sessionId, e);
        }
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

        // 逻辑删除：原始 user 消息（含）之后的所有消息（同步扣减会话消息计数）
        int removed = sessionMessageMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SessionMessage>()
                        .eq(SessionMessage::getSessionId, sessionId)
                        .ge(SessionMessage::getSeq, userMsg.getSeq()));
        if (removed > 0) {
            sessionMapper.decrementMessageCount(sessionId, removed);
        }

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
