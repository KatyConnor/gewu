package com.gewu.application.wenshi.reasoning;

import com.gewu.application.wenshi.knowledge.MemoryInjector;
import com.gewu.application.wenshi.knowledge.MemoryRouter;
import com.gewu.domain.wenshi.knowledge.SemanticFragment;
import com.gewu.infrastructure.llm.LlmClient;
import com.gewu.infrastructure.llm.LlmClientFactory;
import com.gewu.infrastructure.llm.LlmRequest;
import com.gewu.infrastructure.llm.LlmResponse;
import com.gewu.infrastructure.llm.Message;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 多 Agent 协调器 - 并行协作执行复杂任务。
 * <p>
 * 与 {@link WenshiReasoningEngine} 的顺序执行不同，本协调器将任务分解后
 * 由多个 Agent 并行处理各子目标，通过共享黑板（Blackboard）实现 Agent 间通信。
 * <p>
 * 执行流程：
 * <ol>
 *   <li>Planner 分解任务为子目标</li>
 *   <li>每个子目标分配给一个 Agent，并行执行（CompletableFuture）</li>
 *   <li>Agent 间通过 Blackboard 共享中间结果</li>
 *   <li>收集所有结果并合并</li>
 *   <li>Critic 验证合并结果</li>
 * </ol>
 * 适用于子目标间无强依赖关系的任务。有依赖的子目标仍需顺序执行。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiAgentCoordinator {

    private final Planner planner;
    private final SolverRouter solverRouter;
    private final Critic critic;
    private final MemoryRouter memoryRouter;
    private final MemoryInjector memoryInjector;
    private final LlmClientFactory llmClientFactory;

    @Value("${gewu.wenshi.llm.default-provider:qwen}")
    private String defaultLlmProvider;

    @Value("${gewu.wenshi.llm.default-model:qwen-plus}")
    private String defaultLlmModel;

    /** 单个 Agent 执行超时（秒） */
    private static final long AGENT_TIMEOUT_SECONDS = 30;

    /**
     * 并行协调多 Agent 执行任务。
     *
     * @param request 推理请求
     * @return 协调结果，包含各 Agent 的执行结果和合并答案
     */
    public CoordinationResult coordinate(WenshiReasoningRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("MultiAgentCoordinator.coordinate: message={}", request.getMessage());

        // 1. 任务分解
        WenshiReasoningResult.PlanTree plan = planner.plan(request.getMessage(), request);

        // 2. 记忆路由
        TaskType taskType = TaskType.classify(request.getMessage());
        MemoryRouter.RoutingResult routing = memoryRouter.route(
                request.getTenantId(), request.getUserId(), taskType.getCode(), request.getMessage());

        // 3. 创建共享黑板
        AgentBlackboard blackboard = new AgentBlackboard();
        blackboard.put("task", request.getMessage());
        blackboard.put("tenantId", request.getTenantId());

        // 4. 并行执行子目标
        List<CompletableFuture<AgentResult>> futures = new ArrayList<>();
        for (WenshiReasoningResult.SubgoalNode subgoal : plan.getSubgoals()) {
            futures.add(executeAgentAsync(subgoal, request, routing, blackboard));
        }

        // 5. 等待所有 Agent 完成
        List<AgentResult> agentResults = new ArrayList<>();
        for (CompletableFuture<AgentResult> future : futures) {
            try {
                agentResults.add(future.get(AGENT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } catch (Exception e) {
                log.warn("MultiAgentCoordinator: agent timed out or failed: {}", e.getMessage());
                agentResults.add(AgentResult.builder()
                        .subgoalId("unknown").strategy("TIMEOUT")
                        .output("Agent 执行超时").success(false).build());
            }
        }

        // 6. 合并结果
        StringBuilder combined = new StringBuilder();
        for (AgentResult ar : agentResults) {
            if (ar.getOutput() != null) {
                combined.append(ar.getOutput()).append("\n\n");
            }
        }

        // 7. Critic 验证
        Critic.CriticResult criticResult = critic.evaluate(
                Critic.Solution.builder().output(combined.toString()).build(), request);

        long duration = System.currentTimeMillis() - startTime;

        return CoordinationResult.builder()
                .answer(combined.toString().trim())
                .agentResults(agentResults)
                .blackboard(blackboard)
                .criticPassed(criticResult.isPassed())
                .durationMs(duration)
                .parallelCount(plan.getSubgoals().size())
                .build();
    }

    /**
     * 异步执行单个 Agent 处理一个子目标。
     */
    private CompletableFuture<AgentResult> executeAgentAsync(
            WenshiReasoningResult.SubgoalNode subgoal,
            WenshiReasoningRequest request,
            MemoryRouter.RoutingResult routing,
            AgentBlackboard blackboard) {

        return CompletableFuture.supplyAsync(() -> {
            long agentStart = System.currentTimeMillis();
            SolverRouter.Strategy strategy = solverRouter.selectStrategy(subgoal, request);
            String output = executeByStrategy(strategy, subgoal, routing);
            long agentDuration = System.currentTimeMillis() - agentStart;

            // 将结果写入黑板供其他 Agent 参考
            blackboard.put(subgoal.getId(), output);

            return AgentResult.builder()
                    .subgoalId(subgoal.getId())
                    .subgoalDescription(subgoal.getDescription())
                    .strategy(strategy.name())
                    .output(output)
                    .success(output != null && !output.isBlank())
                    .durationMs(agentDuration)
                    .build();
        });
    }

    /**
     * 根据策略执行子目标。
     */
    private String executeByStrategy(SolverRouter.Strategy strategy,
                                      WenshiReasoningResult.SubgoalNode subgoal,
                                      MemoryRouter.RoutingResult routing) {
        switch (strategy) {
            case KNOWLEDGE_LOOKUP: {
                List<SemanticFragment> fragments = routing.getSemanticMemories();
                if (fragments != null && !fragments.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (SemanticFragment f : fragments) {
                        if (f.getContent() != null) sb.append(f.getContent()).append("\n");
                    }
                    return sb.toString().trim();
                }
                return callLlm(subgoal.getDescription(), routing);
            }
            case TOOL_EXECUTION:
            case EXPERIENCE_REUSE:
            case LLM_REASONING:
            default:
                return callLlm(subgoal.getDescription(), routing);
        }
    }

    private String callLlm(String description, MemoryRouter.RoutingResult routing) {
        try {
            LlmClient client = llmClientFactory.getClient(defaultLlmProvider);
            MemoryInjector.InjectionPlan injection = memoryInjector.prepareInjection(routing);
            String systemPrompt = "你是格物知行 AI 助手。请根据以下知识上下文回答问题。";
            if (injection.getSummary() != null && !injection.getSummary().isBlank()) {
                systemPrompt += "\n\n" + injection.getSummary();
            }
            LlmRequest req = LlmRequest.builder()
                    .model(defaultLlmModel)
                    .messages(List.of(
                            Message.builder().role("system").content(systemPrompt).build(),
                            Message.builder().role("user").content(description).build()))
                    .temperature(0.7).maxTokens(2048).stream(false).build();
            LlmResponse resp = client.chat(req);
            return resp.getContent() != null ? resp.getContent() : "";
        } catch (Exception e) {
            log.warn("MultiAgentCoordinator.callLlm failed: {}", e.getMessage());
            return "LLM 推理失败: " + e.getMessage();
        }
    }

    // ==================== 数据结构 ====================

    /**
     * Agent 黑板 - 共享上下文，用于 Agent 间通信。
     */
    @Data
    public static class AgentBlackboard {
        private final ConcurrentHashMap<String, Object> data = new ConcurrentHashMap<>();

        public void put(String key, Object value) { data.put(key, value); }
        public Object get(String key) { return data.get(key); }
        public <T> T get(String key, Class<T> type) {
            Object v = data.get(key);
            return type.isInstance(v) ? type.cast(v) : null;
        }
    }

    @Data
    @Builder
    public static class AgentResult {
        private String subgoalId;
        private String subgoalDescription;
        private String strategy;
        private String output;
        private boolean success;
        private long durationMs;
    }

    @Data
    @Builder
    public static class CoordinationResult {
        private String answer;
        private List<AgentResult> agentResults;
        private AgentBlackboard blackboard;
        private boolean criticPassed;
        private long durationMs;
        private int parallelCount;
    }
}
