package com.gewu.agent.engine.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.AgentEngineException;
import com.gewu.agent.engine.budget.BudgetContext;
import com.gewu.agent.engine.budget.BudgetController;
import com.gewu.agent.engine.budget.BudgetStatus;
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
import com.gewu.agent.engine.spi.PersistenceService;
import com.gewu.agent.engine.spi.SessionContextService;
import com.gewu.agent.engine.spi.ToolConfig;
import com.gewu.agent.engine.tool.ToolContext;
import com.gewu.agent.engine.tool.ToolExecutor;
import com.gewu.agent.engine.tool.ToolResult;
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

    // ==================== 同步执行 ====================

    @Override
    public LlmResponse execute(AgentTask task) {
        String agentId = task.getAgentId();
        AgentSpec agent = loadAgent(agentId);
        String[] pm = resolveProviderAndModel(agent, task);
        log.info("同步对话: agentId={}, provider={}, model={}", agentId, pm[0], pm[1]);

        LlmClient client = llmClientRegistry.getClient(pm[0]);
        List<Message> messages = buildMessages(agent, task);
        Map<String, ToolConfig> toolConfigMap = new LinkedHashMap<>();
        List<ToolDefinition> tools = buildToolDefinitions(agentId, toolConfigMap);
        ToolContext toolContext = buildToolContext(task, agent);
        double temperature = resolveTemperature(task);

        log.info("调用 LLM(同步): provider={}, model={}, messages={}, tools={}, temperature={}",
                pm[0], pm[1], messages.size(), tools.size(), temperature);

        BudgetContext budget = budgetController.createBudget("L2");

        for (int round = 0; round < config.getMaxToolRounds(); round++) {
            // 预算检查：熔断则终止
            if (budgetController.shouldStop(budget)) {
                log.warn("预算熔断: tokenUtil={}, timeUtil={}, round={}",
                        budget.getTokenUtilization(), budget.getTimeUtilization(), round);
                throw AgentEngineException.of("BUDGET_EXCEEDED",
                        "预算耗尽: token=" + budget.getTokenConsumed() + "/" + budget.getTokenBudget()
                                + ", time=" + budget.getElapsedMs() + "ms/" + budget.getTimeBudgetMs() + "ms");
            }

            LlmRequest llmRequest = LlmRequest.builder()
                    .model(pm[1])
                    .messages(messages)
                    .tools(tools.isEmpty() ? null : tools)
                    .temperature(temperature)
                    .maxTokens(resolveMaxTokens(task))
                    .stream(false)
                    .build();

            LlmResponse response = client.chat(llmRequest);

            // 记录预算消耗
            long tokens = response.getUsage() != null ? response.getUsage().getTotalTokens() : 0;
            budgetController.consume(budget, tokens, tokens * 0.00001);

            if (response.getToolCalls() == null || response.getToolCalls().isEmpty()) {
                storeExperience(task, response.getContent());
                return response;
            }

            messages.add(Message.builder()
                    .role("assistant")
                    .content(response.getContent())
                    .build());

            List<CompletableFuture<ToolResult>> futures = response.getToolCalls().stream()
                    .map(tc -> CompletableFuture.supplyAsync(
                            () -> toolExecutor.execute(tc.getName(), tc.getArguments(), toolContext,
                                    toolConfigMap.get(tc.getName())),
                            config.getToolExecutor()))
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
        String agentId = task.getAgentId();
        AgentSpec agent = loadAgent(agentId);
        String[] pm = resolveProviderAndModel(agent, task);
        log.info("流式对话: agentId={}, provider={}, model={}", agentId, pm[0], pm[1]);

        LlmClient client = llmClientRegistry.getClient(pm[0]);
        List<Message> messages = buildMessages(agent, task);
        Map<String, ToolConfig> toolConfigMap = new LinkedHashMap<>();
        List<ToolDefinition> tools = buildToolDefinitions(agentId, toolConfigMap);
        ToolContext toolContext = buildToolContext(task, agent);
        double temperature = resolveTemperature(task);

        log.info("调用 LLM(流式): provider={}, model={}, messages={}, tools={}, temperature={}",
                pm[0], pm[1], messages.size(), tools.size(), temperature);

        BudgetContext budget = budgetController.createBudget("L2");
        StringBuilder contentTracker = new StringBuilder();
        return Flux.defer(() -> streamRound(client, pm[1], messages, tools, toolConfigMap, toolContext, temperature, 0, budget))
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
                        storeExperience(task, contentTracker.toString());
                    }
                })
                .onErrorResume(e -> {
                    log.error("Agent 流式执行异常: agentId={}", task.getAgentId(), e);
                    return Flux.just(AgentEvent.builder()
                            .type(AgentEvent.ERROR)
                            .errorMessage(e.getMessage() != null ? e.getMessage() : "AI 处理失败")
                            .build());
                });
    }

    private Flux<AgentEvent> streamRound(LlmClient client, String model, List<Message> messages,
                                         List<ToolDefinition> tools, Map<String, ToolConfig> toolConfigMap,
                                         ToolContext toolContext, double temperature, int round,
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
                .maxTokens(resolveMaxTokens(null))
                .stream(true)
                .build();

        StringBuilder contentBuilder = new StringBuilder();
        Map<String, ToolCallAccumulator> toolCallAccumulators = new LinkedHashMap<>();
        String[] finishReasonHolder = {null};

        return client.chatStream(llmRequest)
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
                        return Flux.just(AgentEvent.builder().type(AgentEvent.DONE).build());
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
                                                    toolContext, temperature, round + 1, budget))));
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
        String key = delta.getId() != null ? delta.getId() : "default";
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
}
