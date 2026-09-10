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

        for (int round = 0; round < config.getMaxToolRounds(); round++) {
            // 预算检查：熔断则终止
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
                    .maxTokens(resolveMaxTokens(task))
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
                // 输出安全层：PII 脱敏后再持久化/缓存/返回
                response.setContent(outputSanitizer.checkOutput(response.getContent()));
                storeExperience(task, response.getContent());
                recordSuccess(task, plan.budget);
                // 写入语义缓存（供后续相似请求命中）
                responseCache.put(task.getMessage(), response.getContent(), cacheContext);
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
                            ToolResult r = toolExecutor.execute(tc.getName(), tc.getArguments(), toolContext,
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
        return Flux.defer(() -> streamRound(client, plan.model, messages, tools, toolConfigMap, toolContext, temperature, maxTokens, 0, plan.budget))
                .doOnNext(event -> {
                    if (AgentEvent.CONTENT.equals(event.getType()) && event.getContent() != null) {
                        contentTracker.append(event.getContent());
                    }
                    // 预算告警事件透传
                    if (AgentEvent.BUDGET_EXCEEDED.equals(event.getType())) {
                        storeExperience(task, contentTracker.toString());
                    }
                })
                .doFinally(signal -> {
                    if (signal == reactor.core.publisher.SignalType.ON_COMPLETE) {
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
                                         BudgetContext budget) {
        if (round >= config.getMaxToolRounds()) {
            return Flux.just(AgentEvent.builder()
                    .type(AgentEvent.ERROR)
                    .errorMessage("工具调用轮次超限")
                    .build());
        }

        // 预算熔断检查
        if (budgetController.shouldStop(budget)) {
            log.warn("预算熔断(流式): tokenUtil={}, timeUtil={}, round={}",
                    budget.getTokenUtilization(), budget.getTimeUtilization(), round);
            recordMetricSafe("agent.budget.exceeded", 1, Map.of(
                    "agentId", toolContext.getAgentId() != null ? toolContext.getAgentId() : "unknown",
                    "reason", "stream_loop"));
            return Flux.just(AgentEvent.builder()
                    .type(AgentEvent.BUDGET_EXCEEDED)
                    .errorMessage("预算耗尽: token=" + budget.getTokenConsumed() + "/" + budget.getTokenBudget())
                    .metadata(java.util.Map.of("tokenConsumed", budget.getTokenConsumed(),
                            "tokenBudget", budget.getTokenBudget(),
                            "elapsedMs", budget.getElapsedMs()))
                    .build());
        }

        // 预算告警（首次达到 70% 或 90%）
        BudgetStatus status = budgetController.check(budget);
        if (status == BudgetStatus.ALERT && round > 0) {
            log.info("预算告警(流式): tokenUtil={}", budget.getTokenUtilization());
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

        // OTel 追踪：流式 LLM 调用包装 Span（订阅时开启，流终止时结束）
        return Flux.defer(() -> {
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
                .map(chunk -> {
                    if (chunk.getReasoning() != null && !chunk.getReasoning().isEmpty()) {
                        return AgentEvent.builder()
                                .type(AgentEvent.THINKING)
                                .reasoning(chunk.getReasoning())
                                .build();
                    }
                    if (chunk.getDelta() != null && !chunk.getDelta().isEmpty()) {
                        contentBuilder.append(chunk.getDelta());
                        return AgentEvent.builder()
                                .type(AgentEvent.CONTENT)
                                .content(chunk.getDelta())
                                .build();
                    }
                    if (chunk.getToolCallDelta() != null) {
                        accumulateToolCall(toolCallAccumulators, chunk.getToolCallDelta());
                    }
                    if (chunk.getFinishReason() != null) {
                        finishReasonHolder[0] = chunk.getFinishReason();
                    }
                    return AgentEvent.builder().build();
                })
                .filter(event -> event.getType() != null)
                .startWith(AgentEvent.builder()
                        .type(AgentEvent.STATUS)
                        .content(round == 0 ? "正在思考..." : "正在继续推理...")
                        .build())
                .concatWith(Flux.defer(() -> {
                    String content = contentBuilder.toString();
                    List<ToolCall> toolCalls = assembleToolCalls(toolCallAccumulators);

                    messages.add(Message.builder()
                            .role("assistant")
                            .content(content)
                            .toolCalls(toolCalls.isEmpty() ? null : toolCalls)
                            .build());

                    if (toolCalls.isEmpty()) {
                        if ("length".equals(finishReasonHolder[0]) && content.isBlank()) {
                            log.warn("LLM 回复被截断: finish_reason=length, model={}", model);
                            return Flux.just(AgentEvent.builder()
                                    .type(AgentEvent.ERROR)
                                    .errorMessage("AI 回复被截断：推理过程消耗了全部 token 上限，未生成正式回复。" +
                                            "请增大 max_tokens 或简化问题后重试。")
                                    .build());
                        }
                        // 经验沉淀通知 + 完成事件（T4.5：实际写入在流终止回调，
                        // 此事件告知前端本次交互将沉淀为长期记忆）
                        return Flux.just(
                                AgentEvent.builder().type(AgentEvent.EXPERIENCE_SAVED)
                                        .content("执行经验已沉淀至长期记忆")
                                        .build(),
                                AgentEvent.builder().type(AgentEvent.DONE).build());
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
                                    .flatMap(tc -> Mono.fromFuture(CompletableFuture.supplyAsync(
                                                    () -> toolExecutor.execute(tc.getName(), tc.getArguments(), toolContext,
                                                            toolConfigMap.get(tc.getName())),
                                                    config.getToolExecutor()))
                                            .map(result -> {
                                                String output = result.isSuccess() ? result.getOutput() : result.getError();
                                                synchronized (messages) {
                                                    messages.add(Message.builder()
                                                            .role("tool")
                                                            .content(output)
                                                            .toolCallId(tc.getId())
                                                            .name(tc.getName())
                                                            .build());
                                                }
                                                return AgentEvent.builder()
                                                        .type(AgentEvent.TOOL_RESULT)
                                                        .toolResult(AgentEvent.ToolResultInfo.builder()
                                                                .toolCallId(tc.getId())
                                                                .name(tc.getName())
                                                                .result(output)
                                                                .build())
                                                        .build();
                                            }))
                                    .concatWith(Flux.defer(() ->
                                            streamRound(client, model, messages, tools, toolConfigMap,
                                                    toolContext, temperature, maxTokens, round + 1, budget))));
                }));
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
        // 按复杂度等级创建预算
        BudgetContext budget = budgetController.createBudget(complexity.getLevel());
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
        if (agentId == null || agentId.isBlank()) {
            return new ArrayList<>();
        }
        List<ToolConfig> tools = persistenceService.loadAgentTools(agentId);
        return tools.stream()
                .peek(tool -> toolConfigMap.put(tool.getToolName(), tool))
                .map(tool -> ToolDefinition.builder()
                        .name(tool.getToolName())
                        .description(tool.getDescription())
                        .parameters(tool.getRequestSchema())
                        .build())
                .toList();
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
