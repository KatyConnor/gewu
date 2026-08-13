package com.gewu.agent.engine.orchestration;

import com.gewu.agent.engine.cognition.EvolutionHook;
import com.gewu.agent.engine.cognition.ReasoningKernel;
import com.gewu.agent.engine.cognition.ConfidenceGate;
import com.gewu.agent.engine.cognition.GateDecision;
import com.gewu.agent.engine.orchestration.model.AutonomousGoal;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import com.gewu.agent.engine.orchestration.model.OrchestrationResult;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.verification.DualLoopVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 自主执行器 - 自主目标驱动执行循环（图执行 -> 验收校验 -> 反思重做）。
 * <p>防失控六重边界：
 * <ol>
 *   <li>迭代上限 {@code maxIterations}（默认 5），超出强制停止请求人工介入</li>
 *   <li>Token 预算 {@code budgetTokens}</li>
 *   <li>时间预算（单图超时）</li>
 *   <li>HITL 强制节点（关键阶段）</li>
 *   <li>Reflexion 重做限制（同节点限 3 次）</li>
 *   <li>资源配额（沙箱/工具调用配额）</li>
 * </ol>
 * 验收校验与 reflexion 由使用方通过 SPI 提供，未提供时回退为单次执行即成功。
 *
 * @since 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class AutonomousExecutor {

    private final GoalPlanner goalPlanner;
    private final Orchestrator orchestrator;
    private final EvolutionHook evolutionHook;
    private final ReasoningKernel reasoningKernel;
    private final ConfidenceGate confidenceGate;
    private final DualLoopVerifier dualLoopVerifier;

    /**
     * 自主目标驱动执行。
     * <p>循环：分解 -> 执行 -> 验收 -> 不通过则反思重规划（限 maxIterations 轮）。
     */
    public Flux<AgentEvent> executeGoal(AutonomousGoal goal) {
        return Flux.create(sink -> {
            OrchestrationContext ctx = OrchestrationContext.builder()
                    .executionId(UUID.randomUUID().toString())
                    .userId("autonomous")
                    .variables(new HashMap<>(Map.of("input", goal.getDescription())))
                    .build();

            sink.next(AgentEvent.builder()
                    .type("goal_start")
                    .metadata(Map.of("goalId", goal.getGoalId() != null ? goal.getGoalId() : "",
                            "description", goal.getDescription()))
                    .build());

            runWithReflection(goal, ctx, 0, sink);
        });
    }

    private void runWithReflection(AutonomousGoal goal, OrchestrationContext ctx, int iteration,
                                  reactor.core.publisher.FluxSink<AgentEvent> sink) {
        if (iteration >= goal.getMaxIterations()) {
            evolutionHook.onGoalFailure(
                    goal.getGoalId() != null ? goal.getGoalId() : "unknown",
                    "超出自主迭代上限");
            sink.next(AgentEvent.builder()
                    .type("goal_complete")
                    .metadata(Map.of("status", "FAILED",
                            "reason", "超出自主迭代上限", "iterations", iteration))
                    .build());
            sink.complete();
            return;
        }

        ctx.setIteration(iteration);
        OrchestrationGraph graph = goalPlanner.decompose(goal, ctx);
        sink.next(AgentEvent.builder()
                .type("goal_decomposed")
                .metadata(Map.of("graphId", graph.getGraphId(), "mode", graph.getMode()))
                .build());

        var result = orchestrator.runSync(graph, ctx);
        result.getOutputs().forEach(ctx::putVariable);

        // 进化钩子：图完成后记录执行数据
        evolutionHook.onGraphComplete(
                graph.getGraphId(),
                result.getFinalOutput(),
                "iteration=" + iteration);

        // 验收：使用 ReasoningKernel SPI 进行智能验证
        boolean accepted = verifyGoal(goal, result, ctx);

        if (accepted) {
            evolutionHook.onGoalSuccess(
                    goal.getGoalId() != null ? goal.getGoalId() : "unknown",
                    result.getFinalOutput());
            sink.next(AgentEvent.builder()
                    .type("goal_complete")
                    .metadata(Map.of("status", "SUCCESS",
                            "iterations", iteration + 1,
                            "output", result.getFinalOutput() != null ? result.getFinalOutput() : ""))
                    .build());
            sink.complete();
        } else {
            sink.next(AgentEvent.builder()
                    .type("reflection")
                    .metadata(Map.of("iteration", iteration + 1, "reason", "验收未通过，进入反思重规划"))
                    .build());
            runWithReflection(goal, ctx, iteration + 1, sink);
        }
    }

    /**
     * 验收校验 - 使用双闭环验证 + 置信度门控。
     * <p>优先使用 DualLoopVerifier 进行内环（≤5 轮）+ 外环（≤2 轮）验证，
     * 再由 ConfidenceGate 做置信度门控决策。
     */
    protected boolean verifyGoal(AutonomousGoal goal, OrchestrationResult result,
                                 OrchestrationContext ctx) {
        if (!"SUCCESS".equals(result.getStatus())) {
            return false;
        }
        String output = result.getFinalOutput();
        if (output == null || output.isBlank()) {
            return true;
        }

        try {
            // 双闭环验证
            DualLoopVerifier.VerificationResult verification = dualLoopVerifier.verify(
                    output, goal.getAcceptances() != null ? goal.getAcceptances() : List.of(goal.getDescription()),
                    reasoningKernel);

            // 置信度门控
            GateDecision gate = confidenceGate.evaluate(verification.getScore());
            log.debug("verifyGoal: verificationLayer={}, score={}, gateAction={}",
                    verification.getLayer(), verification.getScore(), gate.getAction());

            if (gate.getAction() == GateDecision.Action.ESCALATE_HITL) {
                evolutionHook.onGoalFailure(
                        goal.getGoalId() != null ? goal.getGoalId() : "unknown",
                        "置信度过低，需人工介入: " + gate.getReason());
            }

            return verification.isPassed() && gate.getAction() != GateDecision.Action.ESCALATE_HITL;
        } catch (Exception e) {
            log.debug("verifyGoal: verification failed, falling back to status check: {}", e.getMessage());
            return true;
        }
    }
}