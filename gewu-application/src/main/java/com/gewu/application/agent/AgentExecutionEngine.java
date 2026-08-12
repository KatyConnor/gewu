package com.gewu.application.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.agent.dto.AgentChunk;
import com.gewu.application.agent.dto.AgentExecutionRequest;
import com.gewu.application.agent.dto.ToolResult;
import com.gewu.application.ai.ModelConfigService;
import com.gewu.application.session.SessionContextService;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.agent.Agent;
import com.gewu.domain.agent.AgentTool;
import com.gewu.domain.session.Session;
import com.gewu.infrastructure.llm.*;
import com.gewu.infrastructure.mapper.AgentMapper;
import com.gewu.infrastructure.mapper.AgentToolMapper;
import com.gewu.infrastructure.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutionEngine {

    private static final int MAX_TOOL_ROUNDS = 10;
    private static final int DEFAULT_HISTORY_LIMIT = 50;
    /**
     * 默认 max_tokens 上限。推理模型（LongCat-2.0、DeepSeek-R1 等）的 reasoning_content
     * token 计入此上限，因此需要足够额度让模型在推理后仍能输出正式回复。
     * 可被 model_config.model_params 中的 max_tokens 覆盖。
     */
    private static final int DEFAULT_MAX_TOKENS = 8192;
    
    // CR-022: 使用有界队列和拒绝策略，避免无界队列导致内存溢出
    // 核心线程数 8，最大线程数 16，队列容量 100，拒绝策略为 CallerRunsPolicy（降级到调用者线程执行）
    private static final ExecutorService TOOL_EXECUTOR = new ThreadPoolExecutor(
            8,                                          // corePoolSize
            16,                                         // maximumPoolSize
            60L, TimeUnit.SECONDS,                      // keepAliveTime
            new ArrayBlockingQueue<>(100),              // workQueue (有界队列)
            r -> {
                Thread t = new Thread(r, "tool-exec");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()   // 拒绝策略：队列满时由调用者线程执行
    );

    private final LlmClientFactory llmClientFactory;
    private final ToolExecutionService toolExecutionService;
    private final SessionContextService sessionContextService;
    private final AgentMapper agentMapper;
    private final AgentToolMapper agentToolMapper;
    private final SessionMapper sessionMapper;
    private final ModelConfigService modelConfigService;
    private final AgentMessageBuilder messageBuilder;
    private final AgentContextBuilder contextBuilder;

    /**
     * 解析模型的 max_tokens：优先从 model_config.model_params 读取，未配置时使用默认值。
     */
    private int resolveMaxTokens(String modelId) {
        Integer configured = modelConfigService.getMaxTokensByModelId(modelId);
        if (configured != null && configured > 0) {
            return configured;
        }
        return DEFAULT_MAX_TOKENS;
    }

    public LlmResponse executeAgent(AgentExecutionRequest request) {
        log.info("同步对话请求: agentId={}, model={}, agentMode={}, thinkingStyle={}, messageLen={}",
                request.getAgentId(), request.getModel(), request.getAgentMode(),
                request.getThinkingStyle(), request.getMessage() != null ? request.getMessage().length() : 0);

        String agentId = resolveAgentId(request.getAgentId(), request.getSessionId());
        Agent agent = loadAgent(agentId);
        String[] pm = messageBuilder.resolveProviderAndModel(agent, request.getModel());
        log.info("解析供应商: provider={}, model={}", pm[0], pm[1]);

        LlmClient client = llmClientFactory.getClient(pm[0]);
        List<Message> messages = messageBuilder.buildMessages(agent, request);
        List<ToolDefinition> tools = buildToolDefinitions(agentId);
        ToolContext toolContext = contextBuilder.buildToolContext(request, agent);
        double temperature = messageBuilder.getTemperatureByMode(request.getAgentMode());

        log.info("调用 LLM(同步): provider={}, model={}, messages={}, tools={}, temperature={}",
                pm[0], pm[1], messages.size(), tools.size(), temperature);

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            LlmRequest llmRequest = LlmRequest.builder()
                    .model(pm[1])
                    .messages(messages)
                    .tools(tools.isEmpty() ? null : tools)
                    .temperature(temperature)
                    .maxTokens(resolveMaxTokens(pm[1]))
                    .stream(false)
                    .build();

            LlmResponse response = client.chat(llmRequest);

            if (response.getToolCalls() == null || response.getToolCalls().isEmpty()) {
                return response;
            }

            messages.add(Message.builder()
                    .role("assistant")
                    .content(response.getContent())
                    .build());

            List<CompletableFuture<ToolResult>> futures = response.getToolCalls().stream()
                    .map(tc -> CompletableFuture.supplyAsync(
                            () -> toolExecutionService.executeTool(tc.getName(), tc.getArguments(), toolContext),
                            TOOL_EXECUTOR))
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

        throw BusinessException.of(ResultCode.AGENT_EXECUTION_FAILED, "工具调用轮次超限");
    }

    public Flux<AgentChunk> executeAgentStream(AgentExecutionRequest request) {
        log.info("流式对话请求: agentId={}, model={}, agentMode={}, thinkingStyle={}, messageLen={}",
                request.getAgentId(), request.getModel(), request.getAgentMode(),
                request.getThinkingStyle(), request.getMessage() != null ? request.getMessage().length() : 0);

        String agentId = resolveAgentId(request.getAgentId(), request.getSessionId());
        Agent agent = loadAgent(agentId);
        String[] pm = messageBuilder.resolveProviderAndModel(agent, request.getModel());
        log.info("解析供应商: provider={}, model={}", pm[0], pm[1]);

        LlmClient client = llmClientFactory.getClient(pm[0]);
        List<Message> messages = messageBuilder.buildMessages(agent, request);
        List<ToolDefinition> tools = buildToolDefinitions(agentId);
        ToolContext toolContext = contextBuilder.buildToolContext(request, agent);
        double temperature = messageBuilder.getTemperatureByMode(request.getAgentMode());

        log.info("调用 LLM(流式): provider={}, model={}, messages={}, tools={}, temperature={}",
                pm[0], pm[1], messages.size(), tools.size(), temperature);

        return Flux.defer(() -> streamRound(client, pm[1], messages, tools, toolContext, temperature, 0))
                .onErrorResume(e -> {
                    log.error("Agent 流式执行异常: agentId={}, model={}", request.getAgentId(), pm[1], e);
                    return Flux.just(AgentChunk.builder()
                            .type("error")
                            .errorMessage(e.getMessage() != null ? e.getMessage() : "AI 处理失败")
                            .build());
                });
    }

    private Flux<AgentChunk> streamRound(LlmClient client, String model, List<Message> messages,
                                         List<ToolDefinition> tools, ToolContext toolContext,
                                         double temperature, int round) {
        if (round >= MAX_TOOL_ROUNDS) {
            return Flux.just(AgentChunk.builder()
                    .type("error")
                    .errorMessage("工具调用轮次超限")
                    .build());
        }

        LlmRequest llmRequest = LlmRequest.builder()
                .model(model)
                .messages(messages)
                .tools(tools.isEmpty() ? null : tools)
                .temperature(temperature)
                .maxTokens(resolveMaxTokens(model))
                .stream(true)
                .build();

        StringBuilder contentBuilder = new StringBuilder();
        Map<String, ToolCallAccumulator> toolCallAccumulators = new LinkedHashMap<>();
        // 追踪 LLM 的 finish_reason，用于检测因 max_tokens 截断导致无内容输出的情况
        String[] finishReasonHolder = {null};

        return client.chatStream(llmRequest)
                .map(chunk -> {
                    // 推理/思考内容：作为 thinking 事件输出，与正式回复分离
                    if (chunk.getReasoning() != null && !chunk.getReasoning().isEmpty()) {
                        return AgentChunk.builder()
                                .type("thinking")
                                .reasoning(chunk.getReasoning())
                                .build();
                    }
                    if (chunk.getDelta() != null && !chunk.getDelta().isEmpty()) {
                        contentBuilder.append(chunk.getDelta());
                        return AgentChunk.builder()
                                .type("content")
                                .content(chunk.getDelta())
                                .build();
                    }
                    if (chunk.getToolCallDelta() != null) {
                        accumulateToolCall(toolCallAccumulators, chunk.getToolCallDelta());
                    }
                    // 捕获 finish_reason（通常在最后一个 chunk 中，无 content/reasoning）
                    if (chunk.getFinishReason() != null) {
                        finishReasonHolder[0] = chunk.getFinishReason();
                    }
                    return AgentChunk.builder().build();
                })
                .filter(chunk -> chunk.getType() != null)
                // 在 LLM 响应前发射 status 事件，让前端立即显示"正在思考"
                .startWith(AgentChunk.builder()
                        .type("status")
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
                        // 推理模型可能因 max_tokens 不足导致全部 token 被推理消耗，未生成任何正式回复
                        if ("length".equals(finishReasonHolder[0]) && content.isBlank()) {
                            log.warn("LLM 回复被截断: finish_reason=length, model={}, 推理消耗了全部 token 上限", model);
                            return Flux.just(AgentChunk.builder()
                                    .type("error")
                                    .errorMessage("AI 回复被截断：推理过程消耗了全部 token 上限，未生成正式回复。" +
                                            "请在模型配置中增大 max_tokens 参数或简化问题后重试。")
                                    .build());
                        }
                        return Flux.just(AgentChunk.builder().type("done").build());
                    }

                    // 发射 tool_call 事件（让前端立即看到工具调用决策）
                    List<AgentChunk> toolCallChunks = new ArrayList<>();
                    for (ToolCall tc : toolCalls) {
                        toolCallChunks.add(AgentChunk.builder()
                                .type("tool_call")
                                .toolCall(AgentChunk.ToolCallInfo.builder()
                                        .id(tc.getId())
                                        .name(tc.getName())
                                        .arguments(tc.getArguments())
                                        .build())
                                .build());
                    }

                    // 发射 tool_executing 事件（让前端立即看到执行中状态）
                    List<AgentChunk> executingChunks = new ArrayList<>();
                    for (ToolCall tc : toolCalls) {
                        executingChunks.add(AgentChunk.builder()
                                .type("tool_executing")
                                .toolCall(AgentChunk.ToolCallInfo.builder()
                                        .id(tc.getId())
                                        .name(tc.getName())
                                        .arguments(tc.getArguments())
                                        .build())
                                .build());
                    }

                    // 响应式工具执行：每个工具完成后立即推送结果，不阻塞线程
                    // 使用 Mono.fromFuture 将 CompletableFuture 转为响应式 Mono，
                    // flatMap 并行执行所有工具，结果按完成顺序逐个推送
                    return Flux.fromIterable(toolCallChunks)
                            .concatWith(Flux.fromIterable(executingChunks))
                            .concatWith(Flux.fromIterable(toolCalls)
                                    .flatMap(tc -> Mono.fromFuture(CompletableFuture.supplyAsync(
                                                    () -> toolExecutionService.executeTool(tc.getName(), tc.getArguments(), toolContext),
                                                    TOOL_EXECUTOR))
                                            .map(result -> {
                                                String output = result.isSuccess() ? result.getOutput() : result.getError();
                                                // 线程安全地添加工具结果到消息列表（flatMap 并行执行，多线程同时写）
                                                synchronized (messages) {
                                                    messages.add(Message.builder()
                                                            .role("tool")
                                                            .content(output)
                                                            .toolCallId(tc.getId())
                                                            .name(tc.getName())
                                                            .build());
                                                }
                                                return AgentChunk.builder()
                                                        .type("tool_result")
                                                        .toolResult(AgentChunk.ToolResultInfo.builder()
                                                                .toolCallId(tc.getId())
                                                                .name(tc.getName())
                                                                .result(output)
                                                                .build())
                                                        .build();
                                            }))
                                    // 所有工具完成后递归下一轮（flatMap 自动等待所有内部 Mono 完成）
                                    .concatWith(Flux.defer(() ->
                                            streamRound(client, model, messages, tools, toolContext, temperature, round + 1))));
                }));
    }

    private void accumulateToolCall(Map<String, ToolCallAccumulator> accumulators,
                                    LlmChunk.ToolCallDelta delta) {
        String key = delta.getId() != null ? delta.getId() : "default";
        ToolCallAccumulator acc = accumulators.computeIfAbsent(key, k -> new ToolCallAccumulator());
        if (delta.getId() != null) acc.id = delta.getId();
        if (delta.getName() != null) acc.name = delta.getName();
        if (delta.getArguments() != null) acc.arguments.append(delta.getArguments());
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
     * 解析 agentId：优先用请求传入的 agentId，为空时回退读会话绑定的 agent.
     * <p>使"会话级绑定"生效--前端建会话时绑定 agent，后续对话即使不传 agentId 也能按该 agent 执行.
     */
    private String resolveAgentId(String agentId, String sessionId) {
        if (agentId != null && !agentId.isBlank()) {
            return agentId;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Session session = sessionMapper.selectById(sessionId);
        return session != null ? session.getAgent() : null;
    }

    private Agent loadAgent(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw BusinessException.of(ResultCode.AGENT_NOT_FOUND);
        }
        return agent;
    }

    private List<ToolDefinition> buildToolDefinitions(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return new ArrayList<>();
        }
        List<AgentTool> tools = agentToolMapper.selectList(
                new LambdaQueryWrapper<AgentTool>()
                        .eq(AgentTool::getAgentId, agentId)
                        .eq(AgentTool::getStatus, 1)
                        .orderByAsc(AgentTool::getSortOrder));

        return tools.stream()
                .map(tool -> ToolDefinition.builder()
                        .name(tool.getToolName())
                        .description(tool.getDescription())
                        .parameters(tool.getRequestSchema())
                        .build())
                .toList();
    }
}
