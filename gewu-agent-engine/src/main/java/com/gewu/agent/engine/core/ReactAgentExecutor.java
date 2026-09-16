package com.gewu.agent.engine.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.AgentEngineException;
import com.gewu.agent.engine.budget.BudgetContext;
import com.gewu.agent.engine.budget.BudgetController;
import com.gewu.agent.engine.budget.BudgetStatus;
import com.gewu.agent.engine.cognition.ComplexityRouter;
import com.gewu.agent.engine.cognition.PerceptionEngine;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.llm.LlmClient;
import com.gewu.agent.engine.llm.LlmClientRegistry;
import com.gewu.agent.engine.llm.model.LlmChunk;
import com.gewu.agent.engine.llm.model.LlmRequest;
import com.gewu.agent.engine.llm.model.LlmResponse;
import com.gewu.agent.engine.llm.model.Message;
import com.gewu.agent.engine.llm.model.ToolCall;
import com.gewu.agent.engine.llm.model.ToolDefinition;
import com.gewu.agent.engine.memory.MemoryFragment;
import com.gewu.agent.engine.memory.MemoryRouter;
import com.gewu.agent.engine.memory.MemoryStore;
import com.gewu.agent.engine.message.MessageBuilder;
import com.gewu.agent.engine.message.PromptDirective;
import com.gewu.agent.engine.spi.AgentSpec;
import com.gewu.agent.engine.spi.ModelSelector;
import com.gewu.agent.engine.spi.PersistenceService;
import com.gewu.agent.engine.spi.ResponseCache;
import com.gewu.agent.engine.spi.SessionContextService;
import com.gewu.agent.engine.spi.ToolConfig;
import com.gewu.agent.engine.spi.TraceService;
import com.gewu.agent.engine.spi.MetricService;
import com.gewu.agent.engine.tool.ToolContext;
import com.gewu.agent.engine.tool.ToolExecutor;
import com.gewu.agent.engine.tool.ToolResult;
import com.gewu.agent.engine.tool.security.OutputSanitizer;
import com.gewu.agent.engine.tool.security.PromptInjectionDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * ReAct 执行器 - 框架默认 {@link AgentExecutor} 实现。
 * <p>核心循环：LLM 推理 -> 若请求工具则并行执行 -> 将结果回灌 -> 继续推理，直到 LLM 不再请求工具或达到轮次上限。
 * 支持同步与流式（{@link Flux}<{@link AgentEvent}>）两种模式，流式模式实时推送思考 / 内容 / 工具事件。
 *
 * @since 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class ReactAgentExecutor implements AgentExecutor {

    private final LlmClientRegistry llmClientRegistry;
    private final ToolExecutor toolExecutor;
    private final MessageBuilder messageBuilder;
    private final SessionContextService sessionContextService;
    private final PersistenceService persistenceService;
    private final AgentEngineConfig config;
    private final ObjectMapper objectMapper;
    private final MemoryRouter memoryRouter;
    private final MemoryStore memoryStore;
    private final BudgetController budgetController;
    private final PerceptionEngine perceptionEngine;
    private final ComplexityRouter complexityRouter;
    private final TraceService traceService;
    private final MetricService metricService;
    private final ResponseCache responseCache;
    private final PromptInjectionDetector promptInjectionDetector;
    private final OutputSanitizer outputSanitizer;
    private final ModelSelector modelSelector;
    /** 文件工作空间 SPI（S9 F3）：内置文件工具执行后端，null 时禁用文件工具 */
    private final com.gewu.agent.engine.tool.FileWorkspaceSpi fileWorkspace;

    /** 推理模型截断自愈：finish=length 时的最大重试次数（8192 起步 ×3 次翻倍可达 65536 硬顶） */
    private static final int MAX_TRUNCATION_RETRIES = 3;
    /** max_tokens 硬上限（自动扩大重试的封顶值） */
    private static final int MAX_TOKENS_HARD_CAP = 65536;

    // ==================== 内置工具定义（S9：plan_task / 文件工具） ====================

    /** 内置任务计划工具名（S9 F5）：本地执行，不走 ToolExecutor 外部链路 */
    private static final String PLAN_TOOL_NAME = "plan_task";
    private static final String PLAN_TOOL_DESCRIPTION =
            "创建或更新当前任务的任务清单。开始多步骤工作前调用一次以制定计划；"
                    + "完成某步骤或计划变化时再次调用以更新整体状态（全量覆盖语义）。";
    private static final String PLAN_TOOL_SCHEMA = """
            {"type":"object","properties":{"title":{"type":"string","description":"任务清单标题"},"steps":{"type":"array","description":"任务步骤列表（全量提交，以本次调用为准整体覆盖）","items":{"type":"object","properties":{"id":{"type":"string","description":"步骤唯一标识"},"text":{"type":"string","description":"步骤内容"},"status":{"type":"string","enum":["pending","in_progress","done"],"description":"步骤状态"}},"required":["id","text","status"]}}},"required":["steps"]}
            """;

    /** 内置文件工具（S9 F3）：经 FileWorkspaceSpi 在会话工作空间执行 */
    private static final String READ_FILE_TOOL = "read_file";
    private static final String WRITE_FILE_TOOL = "write_file";
    private static final String EDIT_FILE_TOOL = "edit_file";
    private static final String LIST_DIR_TOOL = "list_dir";
    private static final String READ_FILE_SCHEMA = """
            {"type":"object","properties":{"path":{"type":"string","description":"文件相对路径（相对当前工作空间根目录）"}},"required":["path"]}
            """;
    private static final String WRITE_FILE_SCHEMA = """
            {"type":"object","properties":{"path":{"type":"string","description":"文件相对路径"},"content":{"type":"string","description":"完整文件内容（整体覆盖）"}},"required":["path","content"]}
            """;
    private static final String EDIT_FILE_SCHEMA = """
            {"type":"object","properties":{"path":{"type":"string","description":"文件相对路径"},"old_text":{"type":"string","description":"要替换的原文（需唯一匹配）"},"new_text":{"type":"string","description":"替换后的新文本"}},"required":["path","old_text","new_text"]}
            """;
    private static final String LIST_DIR_SCHEMA = """
            {"type":"object","properties":{"path":{"type":"string","description":"目录相对路径，默认工作空间根目录"}},"required":[]}
            """;

    // ==================== 同步执行 ====================

    @Override
    public LlmResponse execute(AgentTask task) {
        // 输入安全层：提示注入检测（高风险拦截，中风险告警）
        promptInjectionDetector.checkInput(task.getMessage());
        String agentId = task.getAgentId();
        AgentSpec agent = loadAgent(agentId);
        String[] pm = resolveProviderAndModel(agent, task);

        // 感知 -> 复杂度路由 -> 按复杂度预算 -> 模型路由（与流式路径共用决策链）
        ExecutionPlan plan = planExecution(task, pm);
        log.info("同步对话: agentId={}, provider={}, model={}, intentType={}, complexity={}, system={}",
                agentId, plan.provider, plan.model, plan.intent.getIntentType(),
                plan.complexity.getLevel(), plan.complexity.getSystemChoice().getSystem());

        LlmClient client = llmClientRegistry.getClient(plan.provider);
        List<Message> messages = buildMessages(agent, task);
        Map<String, ToolConfig> toolConfigMap = new LinkedHashMap<>();
        List<ToolDefinition> tools = buildToolDefinitions(agentId, toolConfigMap);
        ToolContext toolContext = buildToolContext(task, agent);
        double temperature = resolveTemperature(task);

        // 语义缓存命中检查：高相似历史请求直接返回缓存响应（零 LLM 成本）
        String cacheContext = agentId != null ? agentId : "default";
        String cached = responseCache.get(task.getMessage(), cacheContext);
        if (cached != null && !cached.isBlank()) {
            log.info("语义缓存命中: agentId={}, responseLength={}", agentId, cached.length());
            recordMetricSafe("agent.cache.hit", 1, Map.of("agentId", agentId != null ? agentId : "default"));
            return LlmResponse.builder().content(cached).build();
        }
        recordMetricSafe("agent.cache.miss", 1, Map.of("agentId", agentId != null ? agentId : "default"));

        log.info("调用 LLM(同步): provider={}, model={}, messages={}, tools={}, temperature={}",
                plan.provider, plan.model, messages.size(), tools.size(), temperature);

        // 推理模型思考耗尽预算的自动重试状态（S8：finish=length 且正文为空时加倍 max_tokens）
        int currentMaxTokens = resolveMaxTokens(task);
        int truncationRetries = 0;

        for (int round = 0; round < config.getMaxToolRounds(); round++) {
            // 时间预算滚动续期（S9 方案A）：时间满额但 Token/轮次健康 → 续期不终止
            budgetController.renewTimeBudget(plan.budget);
            // 预算检查：熔断则终止（仅 Token/轮次）
            if (budgetController.shouldStop(plan.budget)) {
                log.warn("预算熔断: tokenUtil={}, timeUtil={}, round={}",
                        plan.budget.getTokenUtilization(), plan.budget.getTimeUtilization(), round);
                recordMetricSafe("agent.budget.exceeded", 1, Map.of(
                        "agentId", agentId != null ? agentId : "unknown", "reason", "sync_loop"));
                throw AgentEngineException.of("BUDGET_EXCEEDED",
                        "预算耗尽: token=" + plan.budget.getTokenConsumed() + "/" + plan.budget.getTokenBudget()
                                + ", time=" + plan.budget.getElapsedMs() + "ms/" + plan.budget.getTimeBudgetMs() + "ms");
            }

            LlmRequest llmRequest = LlmRequest.builder()
                    .model(plan.model)
                    .messages(messages)
                    .tools(tools.isEmpty() ? null : tools)
                    .temperature(temperature)
                    .maxTokens(currentMaxTokens)
                    .stream(false)
                    .build();

            // OTel 追踪：每轮 LLM 调用包装 Span（TraceService SPI 桥接）
            Object llmSpan = traceService.startSpan(task.getSessionId(), task.getAgentId(), "llm_call");
            LlmResponse response;
            try {
                response = client.chat(llmRequest);
                traceService.endSpan(llmSpan);
            } catch (RuntimeException e) {
                traceService.endSpanWithError(llmSpan, e);
                throw e;
            }

            // 记录预算消耗
            long tokens = response.getUsage() != null ? response.getUsage().getTotalTokens() : 0;
            budgetController.consume(plan.budget, tokens, tokens * 0.00001);

            if (response.getToolCalls() == null || response.getToolCalls().isEmpty()) {
                boolean truncated = "length".equals(response.getFinishReason());
                boolean blankContent = response.getContent() == null || response.getContent().isBlank();
                // 推理模型截断自愈（S8/S9）：finish=length 时自动加倍 max_tokens 重试
                // （上限 65536）。S9 起覆盖「正文非空中途截断」——丢弃部分内容整体
                // 重新生成，而非静默返回半截回复。预算熔断（shouldStop）是重试的自然上界。
                if (truncated
                        && truncationRetries < MAX_TRUNCATION_RETRIES
                        && currentMaxTokens < MAX_TOKENS_HARD_CAP) {
                    truncationRetries++;
                    currentMaxTokens = Math.min(currentMaxTokens * 2, MAX_TOKENS_HARD_CAP);
                    // 主动升级预算：token/时间预算随 max_tokens 同步放大，否则重试轮次
                    // 会在循环头的 shouldStop 处被熔断（尤其 L1 级仅 30s 时间预算）。
                    // consume() 会累计 currentRound，重试也占轮次，需同步扩容 maxRounds
                    plan.budget.setTokenBudget(plan.budget.getTokenBudget() * 2);
                    plan.budget.setTimeBudgetMs(Math.max(plan.budget.getTimeBudgetMs() * 2,
                            plan.budget.getElapsedMs() * 3));
                    plan.budget.setMaxRounds(plan.budget.getMaxRounds() + 1);
                    log.warn("推理模型回复被截断（finish=length，正文{}），自动扩大 max_tokens 至 {} 重试（第 {}/{} 次）",
                            blankContent ? "为空" : "不完整", currentMaxTokens, truncationRetries, MAX_TRUNCATION_RETRIES);
                    continue;
                }
                if (truncated && blankContent) {
                    // 重试预算耗尽且无任何正文：明确报错，不再返回空回复
                    throw AgentEngineException.of("TRUNCATED",
                            "AI 回复被截断：推理过程消耗了全部 token 上限，未生成正式回复。" +
                                    "请增大 max_tokens 或简化问题后重试。");
                }
                if (truncated) {
                    // 重试耗尽但已有部分正文：保留部分内容并明示不完整（响应带 finishReason=length）
                    log.warn("截断重试预算耗尽，保留部分回复（finishReason=length）");
                }
                // 输出安全层：PII 脱敏后再持久化/缓存/返回
                response.setContent(outputSanitizer.checkOutput(response.getContent()));
                if (!truncated) {
                    storeExperience(task, response.getContent());
                    // 写入语义缓存（供后续相似请求命中）——截断的部分内容不入缓存
                    responseCache.put(task.getMessage(), response.getContent(), cacheContext);
                }
                recordSuccess(task, plan.budget);
                return response;
            }

            messages.add(Message.builder()
                    .role("assistant")
                    .content(response.getContent())
                    .toolCalls(response.getToolCalls())
                    .build());

            List<CompletableFuture<ToolResult>> futures = response.getToolCalls().stream()
                    .map(tc -> CompletableFuture.supplyAsync(() -> {
                        // OTel 追踪：每次工具执行包装 Span
                        Object toolSpan = traceService.startSpan(task.getSessionId(), tc.getName(), "tool_call");
                    try {
                        ToolResult r = isBuiltinTool(tc.getName())
                                ? executeBuiltinToolSync(tc, toolContext)
                                : toolExecutor.execute(tc.getName(), tc.getArguments(), toolContext,
                                        toolConfigMap.get(tc.getName()));
                        traceService.endSpan(toolSpan);
                        return r;
                    } catch (RuntimeException e) {
                            traceService.endSpanWithError(toolSpan, e);
                            throw e;
                        }
                    }, config.getToolExecutor()))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            for (int i = 0; i < response.getToolCalls().size(); i++) {
                ToolCall toolCall = response.getToolCalls().get(i);
                ToolResult result = futures.get(i).join();
                String output = result.isSuccess() ? result.getOutput() : result.getError();
                messages.add(Message.builder()
                        .role("tool")
                        .content(output)
                        .toolCallId(toolCall.getId())
                        .name(toolCall.getName())
                        .build());
            }
        }

        throw AgentEngineException.of("TOOL_ROUNDS_EXCEEDED", "工具调用轮次超限");
    }

    // ==================== 流式执行 ====================

    @Override
    public Flux<AgentEvent> executeStream(AgentTask task) {
        // 输入安全层：提示注入检测（高风险拦截，中风险告警）
        promptInjectionDetector.checkInput(task.getMessage());
        String agentId = task.getAgentId();
        AgentSpec agent = loadAgent(agentId);
        String[] pm = resolveProviderAndModel(agent, task);

        // 感知 -> 复杂度路由 -> 按复杂度预算 -> 模型路由（与同步路径共用决策链）
        ExecutionPlan plan = planExecution(task, pm);
        log.info("流式对话: agentId={}, provider={}, model={}, intentType={}, complexity={}, system={}",
                agentId, plan.provider, plan.model, plan.intent.getIntentType(),
                plan.complexity.getLevel(), plan.complexity.getSystemChoice().getSystem());

        LlmClient client = llmClientRegistry.getClient(plan.provider);
        List<Message> messages = buildMessages(agent, task);
        Map<String, ToolConfig> toolConfigMap = new LinkedHashMap<>();
        List<ToolDefinition> tools = buildToolDefinitions(agentId, toolConfigMap);
        ToolContext toolContext = buildToolContext(task, agent);
        double temperature = resolveTemperature(task);

        // 语义缓存命中检查：直接以 CONTENT+DONE 事件回放缓存响应（零 LLM 成本）
        String cacheContext = agentId != null ? agentId : "default";
        String cached = responseCache.get(task.getMessage(), cacheContext);
        if (cached != null && !cached.isBlank()) {
            log.info("语义缓存命中(流式): agentId={}, responseLength={}", agentId, cached.length());
            recordMetricSafe("agent.cache.hit", 1, Map.of("agentId", agentId != null ? agentId : "default"));
            return Flux.just(AgentEvent.builder().type(AgentEvent.CONTENT).content(cached).build(),
                    AgentEvent.builder().type(AgentEvent.DONE).build());
        }
        recordMetricSafe("agent.cache.miss", 1, Map.of("agentId", agentId != null ? agentId : "default"));

        log.info("调用 LLM(流式): provider={}, model={}, messages={}, tools={}, temperature={}",
                plan.provider, plan.model, messages.size(), tools.size(), temperature);

        StringBuilder contentTracker = new StringBuilder();
        int maxTokens = resolveMaxTokens(task);
        // 账本准确性（S9 方案A）：预算熔断的运行不算成功，且部分内容不沉淀经验
        java.util.concurrent.atomic.AtomicBoolean budgetStopped = new java.util.concurrent.atomic.AtomicBoolean(false);
        return Flux.defer(() -> streamRound(client, plan.model, messages, tools, toolConfigMap, toolContext, temperature, maxTokens, 0, 0, plan.budget))
                .doOnNext(event -> {
                    if (AgentEvent.CONTENT.equals(event.getType()) && event.getContent() != null) {
                        contentTracker.append(event.getContent());
                    }
                    if (AgentEvent.BUDGET_EXCEEDED.equals(event.getType())) {
                        budgetStopped.set(true);
                    }
                })
                .doFinally(signal -> {
                    if (signal == reactor.core.publisher.SignalType.ON_COMPLETE) {
                        if (budgetStopped.get()) {
                            // 预算熔断：记失败账本，部分内容不沉淀经验（避免不完整回复污染记忆）
                            recordFailure(task, "BUDGET_EXCEEDED: 预算耗尽，已保留部分进度");
                            return;
                        }
                        // 输出安全层：PII 脱敏后再持久化经验
                        storeExperience(task, outputSanitizer.checkOutput(contentTracker.toString()));
                        recordSuccess(task, plan.budget);
                    }
                })
                .onErrorResume(e -> {
                    log.error("Agent 流式执行异常: agentId={}", task.getAgentId(), e);
                    String reason = e.getMessage() != null ? e.getMessage() : "stream_error";
                    recordFailure(task, reason);
                    return Flux.just(
                            AgentEvent.builder()
                                    .type(AgentEvent.FAILURE_RECORDED)
                                    .content("失败已记录：" + reason)
                                    .build(),
                            AgentEvent.builder()
                                    .type(AgentEvent.ERROR)
                                    .errorMessage(e.getMessage() != null ? e.getMessage() : "AI 处理失败")
                                    .build());
                });
    }

    private Flux<AgentEvent> streamRound(LlmClient client, String model, List<Message> messages,
                                         List<ToolDefinition> tools, Map<String, ToolConfig> toolConfigMap,
                                         ToolContext toolContext, double temperature, int maxTokens, int round,
                                         int truncationRetries, BudgetContext budget) {
        if (round >= config.getMaxToolRounds()) {
            return Flux.just(AgentEvent.builder()
                    .type(AgentEvent.ERROR)
                    .errorMessage("工具调用轮次超限")
                    .build());
        }

        // 时间预算滚动续期（S9 方案A）：时间满额但 Token/轮次健康 → 续期并发告警事件，
        // 不终止——持续健康推进的长任务（马拉松思考/多轮工具）不再被墙钟误杀
        java.util.List<AgentEvent> budgetHeadEvents = new ArrayList<>();
        if (budget.getTimeUtilization() >= 1.0 && budgetController.renewTimeBudget(budget)) {
            budgetHeadEvents.add(AgentEvent.builder()
                    .type(AgentEvent.BUDGET_WARNING)
                    .content(String.format("执行时间较长，已自动续期时间预算（累计 %d 秒），任务继续推进中",
                            budget.getElapsedMs() / 1000))
                    .build());
        }

        // 预算熔断检查（仅 Token 超限 / 轮次超限阻断）
        if (budgetController.shouldStop(budget)) {
            log.warn("预算熔断(流式): tokenUtil={}, timeUtil={}, round={}",
                    budget.getTokenUtilization(), budget.getTimeUtilization(), round);
            recordMetricSafe("agent.budget.exceeded", 1, Map.of(
                    "agentId", toolContext.getAgentId() != null ? toolContext.getAgentId() : "unknown",
                    "reason", "stream_loop"));
            // 熔断后补发 done（finishReason=budget）保证前端生命周期完整：
            // 此前流静默结束且 budget_exceeded 被前端忽略，表现为「无报错无结果地断开」
            budgetHeadEvents.add(AgentEvent.builder()
                    .type(AgentEvent.BUDGET_EXCEEDED)
                    .errorMessage("预算耗尽: token=" + budget.getTokenConsumed() + "/" + budget.getTokenBudget())
                    .metadata(java.util.Map.of("tokenConsumed", budget.getTokenConsumed(),
                            "tokenBudget", budget.getTokenBudget(),
                            "elapsedMs", budget.getElapsedMs()))
                    .build());
            budgetHeadEvents.add(AgentEvent.builder().type(AgentEvent.DONE).finishReason("budget").build());
            return Flux.fromIterable(budgetHeadEvents);
        }

        // 预算告警（70%/90% 阈值，budget_warning 事件对用户可见）
        BudgetStatus status = budgetController.check(budget);
        if ((status == BudgetStatus.ALERT || status == BudgetStatus.DEGRADE)) {
            log.info("预算告警(流式): tokenUtil={}, timeUtil={}, round={}",
                    budget.getTokenUtilization(), budget.getTimeUtilization(), round);
            budgetHeadEvents.add(AgentEvent.builder()
                    .type(AgentEvent.BUDGET_WARNING)
                    .content(String.format("任务预算使用率已达 %d%%（token %d%% / 时间滚动续期中）",
                            (int) Math.round(Math.max(budget.getTokenUtilization(), budget.getTimeUtilization()) * 100),
                            (int) Math.round(budget.getTokenUtilization() * 100)))
                    .build());
        }

        LlmRequest llmRequest = LlmRequest.builder()
                .model(model)
                .messages(messages)
                .tools(tools.isEmpty() ? null : tools)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .stream(true)
                .build();

        StringBuilder contentBuilder = new StringBuilder();
        Map<String, ToolCallAccumulator> toolCallAccumulators = new LinkedHashMap<>();
        String[] finishReasonHolder = {null};
        long[] reasoningChars = {0};

        // OTel 追踪：流式 LLM 调用包装 Span（订阅时开启，流终止时结束）
        Flux<AgentEvent> roundFlux = Flux.defer(() -> {
                    Object llmSpan = traceService.startSpan(toolContext.getSessionId(), toolContext.getAgentId(), "llm_call_stream");
                    return client.chatStream(llmRequest)
                            .doFinally(signal -> {
                                if (signal == reactor.core.publisher.SignalType.ON_ERROR) {
                                    traceService.endSpanWithError(llmSpan, null);
                                } else {
                                    traceService.endSpan(llmSpan);
                                }
                            });
                })
                .flatMapIterable(chunk -> {
                    // 先记录 finish_reason 再处理增量：部分供应商把 finish_reason 放在
                    // 最后一个 content/reasoning chunk 上，若先 return 会丢失截断信号
                    if (chunk.getFinishReason() != null) {
                        finishReasonHolder[0] = chunk.getFinishReason();
                    }
                    List<AgentEvent> events = new ArrayList<>(2);
                    if (chunk.getReasoning() != null && !chunk.getReasoning().isEmpty()) {
                        reasoningChars[0] += chunk.getReasoning().length();
                        events.add(AgentEvent.builder()
                                .type(AgentEvent.THINKING)
                                .reasoning(chunk.getReasoning())
                                .build());
                    }
                    if (chunk.getDelta() != null && !chunk.getDelta().isEmpty()) {
                        contentBuilder.append(chunk.getDelta());
                        events.add(AgentEvent.builder()
                                .type(AgentEvent.CONTENT)
                                .content(chunk.getDelta())
                                .build());
                    }
                    if (chunk.getToolCallDelta() != null) {
                        accumulateToolCall(toolCallAccumulators, chunk.getToolCallDelta());
                    }
                    return events;
                })
                .filter(event -> event.getType() != null)
                .startWith(AgentEvent.builder()
                        .type(AgentEvent.STATUS)
                        .content(round == 0 ? "正在思考..." : "正在继续推理...")
                        .build())
                .concatWith(Flux.defer(() -> {
                    String content = contentBuilder.toString();
                    List<ToolCall> toolCalls = assembleToolCalls(toolCallAccumulators);

                    // 流式 Token 记账（S9 方案A）：流式路径此前从不 consume，Token 预算
                    // 形同虚设（tokenUtil 恒 0）；按字符量估算本轮消耗（CJK 约 3 字符/token）
                    {
                        long chars = reasoningChars[0] + content.length();
                        for (Message m : messages) {
                            chars += m.getContent() != null ? m.getContent().length() : 0;
                        }
                        long estimated = Math.max(1, chars / 3);
                        budgetController.consume(budget, estimated, estimated * 0.00001);
                    }

                    messages.add(Message.builder()
                            .role("assistant")
                            .content(content)
                            .toolCalls(toolCalls.isEmpty() ? null : toolCalls)
                            .build());

                    if (toolCalls.isEmpty()) {
                        boolean truncated = "length".equals(finishReasonHolder[0]);
                        boolean blankContent = content.isBlank();
                        // 推理模型截断自愈（S8/S9）：finish=length 时自动加倍 max_tokens 重试。
                        // S9 起覆盖「正文非空中途截断」：先发 CONTENT_RESET 通知前端清空已
                        // 流出的部分正文，再整体重新生成，而非静默结束在半截回复上。
                        if (truncated
                                && truncationRetries < MAX_TRUNCATION_RETRIES
                                && maxTokens < MAX_TOKENS_HARD_CAP) {
                            int nextMaxTokens = Math.min(maxTokens * 2, MAX_TOKENS_HARD_CAP);
                            log.warn("推理模型回复被截断（finish=length，正文{}），自动扩大 max_tokens 至 {} 重试（第 {}/{} 次）: model={}",
                                    blankContent ? "为空" : "不完整",
                                    nextMaxTokens, truncationRetries + 1, MAX_TRUNCATION_RETRIES, model);
                            // 移除本轮的空/半截 assistant 消息避免污染上下文
                            if (!messages.isEmpty()) {
                                messages.remove(messages.size() - 1);
                            }
                            // 主动升级预算：重试轮次的 token/时间预算随 max_tokens 同步放大，
                            // 否则递归入口的 shouldStop 会因时间预算（L1 仅 30s）直接熔断。
                            // consume() 累计 currentRound，重试也占轮次，需同步扩容 maxRounds
                            budget.setTokenBudget(budget.getTokenBudget() * 2);
                            budget.setTimeBudgetMs(Math.max(budget.getTimeBudgetMs() * 2,
                                    budget.getElapsedMs() * 3));
                            budget.setMaxRounds(budget.getMaxRounds() + 1);
                            // 正文已部分流出时，通知调用方清空累积内容（旧内容将被重新生成替换）
                            AgentEvent resetEvent = blankContent ? null : AgentEvent.builder()
                                    .type(AgentEvent.CONTENT_RESET)
                                    .content("回复被截断，正在重新生成")
                                    .build();
                            AgentEvent statusEvent = AgentEvent.builder()
                                    .type(AgentEvent.STATUS)
                                    .content("回复超限，正在扩大预算重新生成...")
                                    .build();
                            Flux<AgentEvent> retryHead = resetEvent != null
                                    ? Flux.just(resetEvent, statusEvent)
                                    : Flux.just(statusEvent);
                            return retryHead.concatWith(Flux.defer(() ->
                                    streamRound(client, model, messages, tools, toolConfigMap, toolContext,
                                            temperature, nextMaxTokens, round, truncationRetries + 1, budget)));
                        }
                        if (truncated && blankContent) {
                            log.warn("LLM 回复被截断且重试预算耗尽: finish_reason=length, model={}", model);
                            return Flux.just(AgentEvent.builder()
                                    .type(AgentEvent.ERROR)
                                    .errorMessage("AI 回复被截断：推理过程消耗了全部 token 上限，未生成正式回复。" +
                                            "请增大 max_tokens 或简化问题后重试。")
                                    .build());
                        }
                        if (truncated) {
                            // 重试耗尽但已有部分正文：保留内容，STATUS 明示不完整，done 带 finishReason=length
                            log.warn("截断重试预算耗尽，保留部分回复: model={}", model);
                            return Flux.just(
                                    AgentEvent.builder().type(AgentEvent.STATUS)
                                            .content("回复因 token 上限被截断，可能不完整")
                                            .build(),
                                    AgentEvent.builder().type(AgentEvent.EXPERIENCE_SAVED)
                                            .content("执行经验已沉淀至长期记忆")
                                            .build(),
                                    doneEvent(toolContext, finishReasonHolder[0]));
                        }
                        // 经验沉淀通知 + 完成事件（T4.5：实际写入在流终止回调，
                        // 此事件告知前端本次交互将沉淀为长期记忆）
                        return Flux.just(
                                AgentEvent.builder().type(AgentEvent.EXPERIENCE_SAVED)
                                        .content("执行经验已沉淀至长期记忆")
                                        .build(),
                                doneEvent(toolContext, finishReasonHolder[0]));
                    }

                    List<AgentEvent> toolCallEvents = new ArrayList<>();
                    for (ToolCall tc : toolCalls) {
                        toolCallEvents.add(AgentEvent.builder()
                                .type(AgentEvent.TOOL_CALL)
                                .toolCall(AgentEvent.ToolCallInfo.builder()
                                        .id(tc.getId()).name(tc.getName()).arguments(tc.getArguments())
                                        .build())
                                .build());
                    }
                    List<AgentEvent> executingEvents = new ArrayList<>();
                    for (ToolCall tc : toolCalls) {
                        executingEvents.add(AgentEvent.builder()
                                .type(AgentEvent.TOOL_EXECUTING)
                                .toolCall(AgentEvent.ToolCallInfo.builder()
                                        .id(tc.getId()).name(tc.getName()).arguments(tc.getArguments())
                                        .build())
                                .build());
                    }

                    return Flux.fromIterable(toolCallEvents)
                            .concatWith(Flux.fromIterable(executingEvents))
                            .concatWith(Flux.fromIterable(toolCalls)
                                    .flatMap(tc -> {
                                        // 内置工具（S9）：plan_task（F5）与文件工具（F3）
                                        // 本地/SPI 执行，不走 ToolExecutor 外部链路
                                        if (isBuiltinTool(tc.getName())) {
                                            return executeBuiltinToolStream(tc, toolContext, messages);
                                        }
                                        return Mono.fromFuture(CompletableFuture.supplyAsync(
                                                        () -> toolExecutor.execute(tc.getName(), tc.getArguments(), toolContext,
                                                                toolConfigMap.get(tc.getName())),
                                                        config.getToolExecutor()))
                                                .map(result -> {
                                                    String output = result.isSuccess() ? result.getOutput() : result.getError();
                                                    appendToolMessage(messages, tc, output);
                                                    return toolResultEvent(tc, output);
                                                });
                                    })
                                    .concatWith(Flux.defer(() ->
                                            streamRound(client, model, messages, tools, toolConfigMap,
                                                    toolContext, temperature, maxTokens, round + 1, truncationRetries, budget))));
                }));
        // 轮次头产生的预算事件（续期/告警/熔断）前置到本轮流输出
        return budgetHeadEvents.isEmpty()
                ? roundFlux
                : Flux.concat(Flux.fromIterable(budgetHeadEvents), roundFlux);
    }

    // ==================== 内置工具执行（S9：plan_task / 文件工具） ====================

    private boolean isBuiltinTool(String name) {
        return PLAN_TOOL_NAME.equals(name)
                || READ_FILE_TOOL.equals(name)
                || WRITE_FILE_TOOL.equals(name)
                || EDIT_FILE_TOOL.equals(name)
                || LIST_DIR_TOOL.equals(name);
    }

    /** 同步路径内置工具分发 */
    private ToolResult executeBuiltinToolSync(ToolCall tc, ToolContext toolContext) {
        if (PLAN_TOOL_NAME.equals(tc.getName())) {
            return executePlanToolSync(tc.getArguments(), toolContext);
        }
        return executeFileTool(tc, toolContext);
    }

    /** 流式路径内置工具分发：plan_task 发计划事件；文件工具走 SPI（阻塞 IO 调度到工具执行池） */
    private Flux<AgentEvent> executeBuiltinToolStream(ToolCall tc, ToolContext toolContext, List<Message> messages) {
        if (PLAN_TOOL_NAME.equals(tc.getName())) {
            return executePlanToolStream(tc, toolContext, messages);
        }
        return Mono.fromFuture(CompletableFuture.supplyAsync(
                        () -> executeFileTool(tc, toolContext), config.getToolExecutor()))
                .map(result -> {
                    String output = result.isSuccess() ? result.getOutput() : result.getError();
                    appendToolMessage(messages, tc, output);
                    return toolResultEvent(tc, output);
                })
                .flux();
    }

    /** 文件工具执行（S9 F3）：read/write/edit/list，经 FileWorkspaceSpi 路由到会话工作空间 */
    private ToolResult executeFileTool(ToolCall tc, ToolContext toolContext) {
        if (fileWorkspace == null || !fileWorkspace.available()) {
            return ToolResult.builder().success(false).error("文件工具未配置").build();
        }
        String name = tc.getName();
        String args = tc.getArguments() != null ? tc.getArguments() : "{}";
        try {
            JsonNode root = objectMapper.readTree(args);
            String path = root.path("path").asText("");
            if (path.isBlank()) {
                return ToolResult.builder().success(false).error("缺少 path 参数").build();
            }
            switch (name) {
                case READ_FILE_TOOL -> {
                    String content = fileWorkspace.readFile(toolContext, path);
                    if (content == null) {
                        return ToolResult.builder().success(false).error("文件不存在: " + path).build();
                    }
                    // 内容截断保护（与外发工具同款输出上限策略，默认 10KB）
                    return ToolResult.builder().success(true).output(truncate(content, 10 * 1024)).build();
                }
                case WRITE_FILE_TOOL -> {
                    String content = root.path("content").asText("");
                    fileWorkspace.writeFile(toolContext, path, content);
                    return ToolResult.builder().success(true)
                            .output("已写入 " + path + "（" + content.length() + " 字符）").build();
                }
                case EDIT_FILE_TOOL -> {
                    String oldText = root.path("old_text").asText("");
                    String newText = root.path("new_text").asText("");
                    if (oldText.isEmpty()) {
                        return ToolResult.builder().success(false).error("缺少 old_text 参数").build();
                    }
                    String current = fileWorkspace.readFile(toolContext, path);
                    if (current == null) {
                        return ToolResult.builder().success(false).error("文件不存在: " + path).build();
                    }
                    int idx = current.indexOf(oldText);
                    if (idx < 0) {
                        return ToolResult.builder().success(false)
                                .error("未找到要替换的文本（old_text 不匹配）: " + path).build();
                    }
                    if (current.indexOf(oldText, idx + 1) >= 0) {
                        return ToolResult.builder().success(false)
                                .error("old_text 在文件中多处匹配，请提供更长的唯一上下文").build();
                    }
                    String updated = current.substring(0, idx) + newText + current.substring(idx + oldText.length());
                    fileWorkspace.writeFile(toolContext, path, updated);
                    return ToolResult.builder().success(true)
                            .output("已编辑 " + path + "（替换 1 处）").build();
                }
                case LIST_DIR_TOOL -> {
                    List<String> entries = fileWorkspace.listDir(toolContext, path);
                    if (entries.isEmpty()) {
                        return ToolResult.builder().success(true).output("(空目录)").build();
                    }
                    return ToolResult.builder().success(true)
                            .output(truncate(String.join("\n", entries), 4096)).build();
                }
                default -> {
                    return ToolResult.builder().success(false).error("未知内置工具: " + name).build();
                }
            }
        } catch (Exception e) {
            return ToolResult.builder().success(false).error("文件操作失败: " + e.getMessage()).build();
        }
    }

    /** 流式路径执行 plan_task：发计划事件（首次创建/后续更新）+ 工具结果事件，并回灌 tool 消息 */
    private Flux<AgentEvent> executePlanToolStream(ToolCall tc, ToolContext toolContext, List<Message> messages) {
        boolean firstCall;
        try {
            JsonNode root = objectMapper.readTree(tc.getArguments() != null ? tc.getArguments() : "{}");
            ToolContext.PlanState state = planStateOf(toolContext);
            firstCall = state.getSteps().isEmpty();
            applyPlanState(state, root);
            String output = "任务清单已更新：" + state.getSteps().size() + " 项步骤";
            appendToolMessage(messages, tc, output);
            return Flux.just(
                    AgentEvent.builder()
                            .type(firstCall ? AgentEvent.PLAN_CREATED : AgentEvent.PLAN_UPDATED)
                            .planTitle(state.getTitle())
                            .plan(state.getSteps())
                            .build(),
                    toolResultEvent(tc, output));
        } catch (Exception e) {
            log.warn("plan_task 解析失败: {}", e.getMessage());
            String output = "计划解析失败: " + e.getMessage();
            appendToolMessage(messages, tc, output);
            return Flux.just(toolResultEvent(tc, output));
        }
    }

    /** 同步路径执行 plan_task：仅更新计划状态并返回工具结果（同步无事件流） */
    private ToolResult executePlanToolSync(String arguments, ToolContext toolContext) {
        try {
            JsonNode root = objectMapper.readTree(arguments != null ? arguments : "{}");
            ToolContext.PlanState state = planStateOf(toolContext);
            applyPlanState(state, root);
            return ToolResult.builder().success(true)
                    .output("任务清单已更新：" + state.getSteps().size() + " 项步骤")
                    .build();
        } catch (Exception e) {
            return ToolResult.builder().success(false).error("计划解析失败: " + e.getMessage()).build();
        }
    }

    private ToolContext.PlanState planStateOf(ToolContext toolContext) {
        if (toolContext.getPlanState() == null) {
            toolContext.setPlanState(new ToolContext.PlanState());
        }
        return toolContext.getPlanState();
    }

    private void applyPlanState(ToolContext.PlanState state, JsonNode root) {
        List<AgentEvent.PlanStepInfo> steps = new ArrayList<>();
        JsonNode stepsNode = root.path("steps");
        if (stepsNode.isArray()) {
            int i = 0;
            for (JsonNode s : stepsNode) {
                i++;
                steps.add(AgentEvent.PlanStepInfo.builder()
                        .id(s.path("id").asText(String.valueOf(i)))
                        .text(s.path("text").asText(""))
                        .status(s.path("status").asText("pending"))
                        .build());
            }
        }
        String title = root.path("title").asText("");
        if (!title.isBlank()) {
            state.setTitle(title);
        }
        state.setSteps(steps);
    }

    private void appendToolMessage(List<Message> messages, ToolCall tc, String output) {
        synchronized (messages) {
            messages.add(Message.builder()
                    .role("tool")
                    .content(output)
                    .toolCallId(tc.getId())
                    .name(tc.getName())
                    .build());
        }
    }

    private AgentEvent toolResultEvent(ToolCall tc, String output) {
        return AgentEvent.builder()
                .type(AgentEvent.TOOL_RESULT)
                .toolResult(AgentEvent.ToolResultInfo.builder()
                        .toolCallId(tc.getId())
                        .name(tc.getName())
                        .result(output)
                        .build())
                .build();
    }

    /** done 事件：携带 finishReason 与最终任务计划快照（S9 F5，前端历史回放用） */
    private AgentEvent doneEvent(ToolContext toolContext, String finishReason) {
        AgentEvent.AgentEventBuilder builder = AgentEvent.builder()
                .type(AgentEvent.DONE)
                .finishReason(finishReason);
        ToolContext.PlanState state = toolContext != null ? toolContext.getPlanState() : null;
        if (state != null && !state.getSteps().isEmpty()) {
            builder.planTitle(state.getTitle())
                    .plan(state.getSteps());
        }
        return builder.build();
    }

    // ==================== 辅助方法 ====================

    private AgentSpec loadAgent(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        return persistenceService.loadAgent(agentId);
    }

    private String[] resolveProviderAndModel(AgentSpec agent, AgentTask task) {
        if (task.getModelProvider() != null && task.getModelName() != null) {
            return new String[]{task.getModelProvider(), task.getModelName()};
        }
        if (agent != null && agent.getModelProvider() != null && agent.getModelName() != null) {
            return new String[]{agent.getModelProvider(), agent.getModelName()};
        }
        throw AgentEngineException.of("MODEL_NOT_RESOLVED",
                "无法解析 LLM 供应商与模型，请在 AgentTask 或 AgentSpec 中指定 modelProvider 与 modelName");
    }

    /**
     * 共用前置决策链：感知 -> 复杂度路由 -> 按复杂度创建预算 -> 模型路由。
     * 同步与流式两条路径共用，保证两条路径产出一致的 provider/model 决策。
     */
    private ExecutionPlan planExecution(AgentTask task, String[] pm) {
        // 感知引擎：原始输入 -> 结构化意图（结果缓存到 task 供后续环节使用）
        PerceptionEngine.Intent intent = perceptionEngine.perceive(task.getMessage());
        task.setIntent(intent);
        // 复杂度路由：意图 + 描述特征 -> System 1/2 决策
        ComplexityRouter.ComplexityResult complexity = complexityRouter.route(task.getMessage(), intent);
        // 会话对话的复杂度预算下限（用户实报 199s/30s=665% 熔断）：路由按消息文本评分，
        // 「继续执行未完成的任务」这类短消息会被判 L1（30s 时间预算），但多轮会话
        // 携带完整任务上下文且带工具循环，实际是重任务——session 非空时至少 L2。
        // L1 只保留给无会话的裸轻量调用。
        String budgetLevel = complexity.getLevel();
        if (task.getSessionId() != null && !task.getSessionId().isBlank() && "L1".equals(budgetLevel)) {
            budgetLevel = "L2";
            log.info("复杂度预算下限提升: 会话任务 L1 -> L2 (sessionId={})", task.getSessionId());
        }
        // 按复杂度等级创建预算
        BudgetContext budget = budgetController.createBudget(budgetLevel);
        // 模型路由：调用方未显式指定模型时按复杂度/预算选择最优模型
        String[] routed = routeModelIfApplicable(task, pm, complexity, budget);
        return new ExecutionPlan(intent, complexity, budget, routed[0], routed[1]);
    }

    /** 一次执行的前置决策结果：意图、复杂度、预算与最终 provider/model。 */
    private static final class ExecutionPlan {
        final PerceptionEngine.Intent intent;
        final ComplexityRouter.ComplexityResult complexity;
        final BudgetContext budget;
        final String provider;
        final String model;

        ExecutionPlan(PerceptionEngine.Intent intent, ComplexityRouter.ComplexityResult complexity,
                      BudgetContext budget, String provider, String model) {
            this.intent = intent;
            this.complexity = complexity;
            this.budget = budget;
            this.provider = provider;
            this.model = model;
        }
    }

    /**
     * 模型路由：调用方显式指定模型时尊重调用方；否则按复杂度评分与剩余预算
     * 咨询 {@link ModelSelector} 选择最优模型。路由失败静默保持原模型。
     */
    private String[] routeModelIfApplicable(AgentTask task, String[] pm,
                                            ComplexityRouter.ComplexityResult complexity,
                                            BudgetContext budget) {
        // 显式模型时默认尊重调用方；modelRouteEnabled=true（基准评测/按需）除外——
        // 仍咨询 ModelSelector，命中更优模型则改选
        boolean routingRequested = Boolean.TRUE.equals(task.getModelRouteEnabled());
        if (!routingRequested && task.getModelProvider() != null && task.getModelName() != null) {
            return pm;
        }
        try {
            long budgetRemaining = budget != null
                    ? Math.max(0, budget.getTokenBudget() - budget.getTokenConsumed())
                    : Long.MAX_VALUE;
            ModelSelector.ModelSelection selection = modelSelector.select(
                    task.getMessage(),
                    complexity != null ? complexity.getScore() : 5,
                    null, 0, budgetRemaining);
            if (selection != null && selection.getModelName() != null
                    && !selection.getModelName().isBlank() && !selection.getModelName().equals(pm[1])) {
                String provider = selection.getModelProvider() != null ? selection.getModelProvider() : pm[0];
                log.info("模型路由: {} -> {} (provider={}, reason={})",
                        pm[1], selection.getModelName(), provider, selection.getReason());
                recordMetricSafe("agent.model.route", 1, Map.of(
                        "from", pm[1], "to", selection.getModelName(),
                        "agentId", task.getAgentId() != null ? task.getAgentId() : "default"));
                return new String[]{provider, selection.getModelName()};
            }
        } catch (Exception e) {
            log.debug("模型路由失败，保持原模型: {}", e.getMessage());
        }
        return pm;
    }

    private List<Message> buildMessages(AgentSpec agent, AgentTask task) {
        List<Message> history = task.getHistory();
        if (history == null && task.getSessionId() != null) {
            history = sessionContextService.buildContextMessages(task.getSessionId(), config.getDefaultHistoryLimit());
        }
        List<Message> messages = messageBuilder.buildMessages(agent, task.getMessage(), history,
                task.getAgentMode(), task.getThinkingStyle());
        // 注入记忆上下文（记忆路由 SPI 按需注入相关记忆到 system 消息）
        String domain = task.getAgentId() != null ? task.getAgentId() : "default";
        return memoryRouter.inject(domain, messages, task.getMessage());
    }

    private List<ToolDefinition> buildToolDefinitions(String agentId, Map<String, ToolConfig> toolConfigMap) {
        List<ToolDefinition> definitions = new ArrayList<>();
        if (agentId != null && !agentId.isBlank()) {
            List<ToolConfig> tools = persistenceService.loadAgentTools(agentId);
            tools.stream()
                    .peek(tool -> toolConfigMap.put(tool.getToolName(), tool))
                    .map(tool -> ToolDefinition.builder()
                            .name(tool.getToolName())
                            .description(tool.getDescription())
                            .parameters(tool.getRequestSchema())
                            .build())
                    .forEach(definitions::add);
        }
        // 内置任务计划工具（S9 F5）：模型自主创建/更新任务清单，前端渲染任务流程卡片
        definitions.add(ToolDefinition.builder()
                .name(PLAN_TOOL_NAME)
                .description(PLAN_TOOL_DESCRIPTION)
                .parameters(PLAN_TOOL_SCHEMA)
                .build());
        // 内置文件工具（S9 F3）：read/write/edit/list，经 FileWorkspaceSpi 在会话工作空间执行
        if (fileWorkspace != null && fileWorkspace.available()) {
            definitions.add(ToolDefinition.builder().name(READ_FILE_TOOL)
                    .description("读取工作空间中的文件内容（相对路径）。")
                    .parameters(READ_FILE_SCHEMA).build());
            definitions.add(ToolDefinition.builder().name(WRITE_FILE_TOOL)
                    .description("创建或覆盖工作空间中的文件（整体写入）。")
                    .parameters(WRITE_FILE_SCHEMA).build());
            definitions.add(ToolDefinition.builder().name(EDIT_FILE_TOOL)
                    .description("精确编辑文件中的一段文本（old_text 需在文件中唯一匹配）。")
                    .parameters(EDIT_FILE_SCHEMA).build());
            definitions.add(ToolDefinition.builder().name(LIST_DIR_TOOL)
                    .description("列出工作空间目录中的文件与子目录。")
                    .parameters(LIST_DIR_SCHEMA).build());
        }
        return definitions;
    }

    private ToolContext buildToolContext(AgentTask task, AgentSpec agent) {
        boolean sandboxEnabled = false;
        String sandboxImage = null;

        if (agent != null && agent.getModelConfig() != null && !agent.getModelConfig().isBlank()) {
            try {
                JsonNode configNode = objectMapper.readTree(agent.getModelConfig());
                if (configNode.has("sandboxEnabled")) {
                    sandboxEnabled = configNode.get("sandboxEnabled").asBoolean(false);
                }
                if (configNode.has("sandboxImage")) {
                    sandboxImage = configNode.get("sandboxImage").asText(null);
                }
            } catch (Exception e) {
                log.warn("解析 Agent modelConfig 失败: agentId={}", agent.getId(), e);
            }
        }

        return ToolContext.builder()
                .userId(task.getUserId())
                .sessionId(task.getSessionId())
                .agentId(task.getAgentId())
                .timeout(30)
                .sandboxEnabled(sandboxEnabled)
                .sandboxImage(sandboxImage)
                .build();
    }

    private double resolveTemperature(AgentTask task) {
        if (task.getTemperature() != null) {
            return task.getTemperature();
        }
        return PromptDirective.getTemperatureByMode(task.getAgentMode());
    }

    private int resolveMaxTokens(AgentTask task) {
        if (task != null && task.getMaxTokens() != null && task.getMaxTokens() > 0) {
            return task.getMaxTokens();
        }
        return config.getDefaultMaxTokens();
    }

    private void accumulateToolCall(Map<String, ToolCallAccumulator> accumulators,
                                    LlmChunk.ToolCallDelta delta) {
        String key;
        if (delta.getId() != null) {
            key = delta.getId();
        } else if (!accumulators.isEmpty()) {
            // 标准 OpenAI 流式协议：id 仅在工具调用首个分块出现，后续分块无 id，
            // 需按顺序关联到最近一次出现的工具调用（accumulators 为插入有序的 LinkedHashMap）
            key = accumulators.keySet().stream().reduce((first, second) -> second).orElse("default");
        } else {
            key = "default";
        }
        ToolCallAccumulator acc = accumulators.computeIfAbsent(key, k -> new ToolCallAccumulator());
        if (delta.getId() != null) {
            acc.id = delta.getId();
        }
        if (delta.getName() != null) {
            acc.name = delta.getName();
        }
        if (delta.getArguments() != null) {
            acc.arguments.append(delta.getArguments());
        }
    }

    private List<ToolCall> assembleToolCalls(Map<String, ToolCallAccumulator> accumulators) {
        List<ToolCall> toolCalls = new ArrayList<>();
        for (ToolCallAccumulator acc : accumulators.values()) {
            if (acc.name != null) {
                toolCalls.add(ToolCall.builder()
                        .id(acc.id)
                        .name(acc.name)
                        .arguments(acc.arguments.toString())
                        .build());
            }
        }
        return toolCalls;
    }

    private static class ToolCallAccumulator {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    /**
     * 将执行经验存储到记忆系统（记忆存储 SPI）。
     * <p>执行完成后自动将用户输入与 AI 输出作为情景记忆持久化，
     * 供后续任务的记忆检索与经验复用使用。
     */
    private void storeExperience(AgentTask task, String output) {
        if (output == null || output.isBlank()) {
            return;
        }
        try {
            String domain = task.getAgentId() != null ? task.getAgentId() : "default";
            String content = "用户: " + truncate(task.getMessage(), 500)
                    + "\nAI: " + truncate(output, 1000);
            MemoryFragment fragment = MemoryFragment.builder()
                    .domain(domain)
                    .type("episodic")
                    .content(content)
                    .build();
            memoryStore.store(fragment);
            log.debug("storeExperience: domain={}, contentLength={}", domain, content.length());
        } catch (Exception e) {
            log.debug("storeExperience failed: {}", e.getMessage());
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /**
     * 记录执行成功：追踪 + 指标（任务成功 + 预算利用率）。
     */
    private void recordSuccess(AgentTask task, BudgetContext budget) {
        try {
            String agentId = task.getAgentId() != null ? task.getAgentId() : "unknown";
            traceService.recordTrace(budget != null ? "budget" : "exec", agentId, "EXECUTE", "llm_complete", "success");
            metricService.recordMetric("agent.task.success", budget != null ? budget.getElapsedMs() : 0,
                    Map.of("agentId", agentId));
            if (budget != null) {
                metricService.recordMetric("agent.budget.utilization", budget.getTokenConsumed(),
                        Map.of("agentId", agentId, "budget", String.valueOf(budget.getTokenBudget())));
            }
        } catch (Exception e) {
            log.debug("recordSuccess failed: {}", e.getMessage());
        }
    }

    /**
     * 记录执行失败：追踪 + 指标（任务失败）。
     */
    private void recordFailure(AgentTask task, String reason) {
        try {
            String agentId = task.getAgentId() != null ? task.getAgentId() : "unknown";
            traceService.recordTrace(agentId, agentId, "EXECUTE", "error", reason);
            metricService.recordMetric("agent.task.failure", 1, Map.of("agentId", agentId, "reason", reason));
        } catch (Exception e) {
            log.debug("recordFailure failed: {}", e.getMessage());
        }
    }

    /** 指标记录（缓存/路由/熔断等观测点，失败静默） */
    private void recordMetricSafe(String name, double value, Map<String, String> tags) {
        try {
            metricService.recordMetric(name, value, tags);
        } catch (Exception e) {
            log.debug("recordMetric failed: {} {}", name, e.getMessage());
        }
    }
}
