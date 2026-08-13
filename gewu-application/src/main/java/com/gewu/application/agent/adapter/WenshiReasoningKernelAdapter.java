package com.gewu.application.agent.adapter;

import com.gewu.agent.engine.cognition.ReasoningKernel;
import com.gewu.agent.engine.cognition.ReasoningResult;
import com.gewu.application.wenshi.reasoning.Critic;
import com.gewu.application.wenshi.reasoning.Planner;
import com.gewu.application.wenshi.reasoning.SolverRouter;
import com.gewu.application.wenshi.reasoning.WenshiReasoningRequest;
import com.gewu.application.wenshi.reasoning.WenshiReasoningResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ReasoningKernel SPI 适配器 - 桥接 Wenshi 推理层到 Agent 引擎。
 * <p>将 Wenshi 的 Planner（规划）、Critic（验证）、SolverRouter（策略路由）
 * 接入 agent-engine 的 {@link ReasoningKernel} SPI，替换 NoOp 默认实现。
 * <p>启用条件：{@code agent.engine.adapter.enabled=true}
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class WenshiReasoningKernelAdapter implements ReasoningKernel {

    private final Planner planner;
    private final Critic critic;
    private final SolverRouter solverRouter;

    /**
     * 规划：委托 Wenshi Planner 将任务分解为子目标列表。
     */
    @Override
    public ReasoningResult plan(String task, String context) {
        try {
            WenshiReasoningRequest request = buildRequest(context);
            WenshiReasoningResult.PlanTree planTree = planner.plan(task, request);
            List<String> subtasks = planTree.getSubgoals() != null
                    ? planTree.getSubgoals().stream()
                            .map(s -> s.getId() + ": " + s.getDescription() + " [" + s.getStrategy() + "]")
                            .collect(Collectors.toList())
                    : List.of(task);
            log.debug("WenshiReasoningKernelAdapter.plan: task={}, subtasks={}", task, subtasks.size());
            return ReasoningResult.builder()
                    .type("PLAN")
                    .subtasks(subtasks)
                    .reasoning("Planner decomposed task into " + subtasks.size() + " subtasks")
                    .build();
        } catch (Exception e) {
            log.warn("WenshiReasoningKernelAdapter.plan failed, falling back to single task: {}", e.getMessage());
            return ReasoningResult.builder()
                    .type("PLAN")
                    .subtasks(List.of(task))
                    .reasoning("Plan failed: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 求解策略路由：委托 Wenshi SolverRouter 选择求解策略。
     */
    @Override
    public String routeSolver(String task) {
        try {
            WenshiReasoningRequest request = buildRequest(null);
            WenshiReasoningResult.SubgoalNode subgoal = WenshiReasoningResult.SubgoalNode.builder()
                    .id("route-0")
                    .description(task)
                    .strategy("LLM_REASONING")
                    .dependencies(List.of())
                    .completed(false)
                    .build();
            SolverRouter.Strategy strategy = solverRouter.selectStrategy(subgoal, request);
            log.debug("WenshiReasoningKernelAdapter.routeSolver: task={}, strategy={}", task, strategy);
            return strategy.name();
        } catch (Exception e) {
            log.warn("WenshiReasoningKernelAdapter.routeSolver failed: {}", e.getMessage());
            return "DIRECT_ANSWER";
        }
    }

    /**
     * 评估：委托 Wenshi Critic 验证执行结果是否达标。
     */
    @Override
    public ReasoningResult critique(String output, List<String> acceptances) {
        try {
            WenshiReasoningRequest request = buildRequest(null);
            Critic.Solution solution = Critic.Solution.builder()
                    .output(output)
                    .type("TEXT")
                    .build();
            Critic.CriticResult result = critic.evaluate(solution, request);
            log.debug("WenshiReasoningKernelAdapter.critique: passed={}, method={}", result.isPassed(), result.getMethod());
            return ReasoningResult.builder()
                    .type("CRITIC")
                    .accepted(result.isPassed())
                    .score(result.isPassed() ? 0.9 : 0.3)
                    .verdict(result.getReason())
                    .reasoning("Critic method: " + result.getMethod() + ", verifiable: " + result.isVerifiable())
                    .build();
        } catch (Exception e) {
            log.warn("WenshiReasoningKernelAdapter.critique failed, defaulting to accept: {}", e.getMessage());
            return ReasoningResult.builder()
                    .type("CRITIC")
                    .accepted(true)
                    .score(0.6)
                    .verdict("Critic unavailable, default pass: " + e.getMessage())
                    .build();
        }
    }

    private WenshiReasoningRequest buildRequest(String context) {
        return WenshiReasoningRequest.builder()
                .message(context)
                .constraints(WenshiReasoningRequest.ReasoningConstraints.defaults())
                .build();
    }
}
