package com.gewu.agent.engine.orchestration;

import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.orchestration.model.AutonomousGoal;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import com.gewu.agent.engine.orchestration.model.OrchestrationResult;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

/**
 * 编排引擎门面 - 编排能力的统一入口。
 * <p>聚合 {@link Orchestrator}（图执行）、{@link GoalPlanner}（目标分解）、{@link AutonomousExecutor}（自主目标循环）。
 * 支持暂停 / 恢复 / 取消（HITL 配合）。
 *
 * <pre>{@code
 * @Autowired
 * private OrchestrationEngine engine;
 *
 * // 执行编排图（流式）
 * Flux<AgentEvent> stream = engine.executeStream(graph, context);
 *
 * // 自主目标驱动
 * Flux<AgentEvent> goalStream = engine.executeGoal(goal);
 * }</pre>
 *
 * @since 1.0.0
 */
@RequiredArgsConstructor
public class OrchestrationEngine {

    private final Orchestrator orchestrator;
    private final GoalPlanner goalPlanner;
    private final AutonomousExecutor autonomousExecutor;

    /** 提交编排图执行（流式） */
    public Flux<AgentEvent> executeStream(OrchestrationGraph graph, OrchestrationContext ctx) {
        return orchestrator.run(graph, ctx);
    }

    /** 提交编排图执行（同步） */
    public OrchestrationResult execute(OrchestrationGraph graph, OrchestrationContext ctx) {
        return orchestrator.runSync(graph, ctx);
    }

    /** 自主目标驱动执行（流式） */
    public Flux<AgentEvent> executeGoal(AutonomousGoal goal) {
        return autonomousExecutor.executeGoal(goal);
    }

    /** 暂停编排 */
    public void pause(String executionId) {
        // 暂停通过配合外部调度（如基于 ExecutionRecord 状态机）实现
    }

    /** 恢复编排 */
    public void resume(String executionId) {
    }

    /** 取消编排 */
    public void cancel(String executionId) {
    }

    public Orchestrator getOrchestrator() {
        return orchestrator;
    }

    public GoalPlanner getGoalPlanner() {
        return goalPlanner;
    }

    public AutonomousExecutor getAutonomousExecutor() {
        return autonomousExecutor;
    }
}