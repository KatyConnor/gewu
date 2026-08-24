package com.gewu.agent.engine.orchestration;

import com.gewu.agent.engine.AgentEngineException;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.orchestration.model.AutonomousGoal;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import com.gewu.agent.engine.orchestration.model.OrchestrationResult;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

import java.util.Optional;

/**
 * 编排引擎门面 - 编排能力的统一入口。
 * <p>聚合 {@link Orchestrator}（图执行）、{@link GoalPlanner}（目标分解）、{@link AutonomousExecutor}（自主目标循环）、
 * {@link ExecutionControl}（协作式暂停/取消与断点续跑）。
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
 *
 * // 协作式控制（当前节点执行完毕后生效）
 * engine.pause(executionId);
 * Flux<AgentEvent> resumed = engine.resume(executionId);   // 从断点继续
 * engine.cancel(executionId);
 * }</pre>
 *
 * @since 1.0.0
 */
@RequiredArgsConstructor
public class OrchestrationEngine {

    private final Orchestrator orchestrator;
    private final GoalPlanner goalPlanner;
    private final AutonomousExecutor autonomousExecutor;
    private final ExecutionControl executionControl;

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

    /**
     * 暂停编排：协作式信号，当前节点执行完毕后生效；
     * 生效时流以 graph_complete(status=PAUSED) 优雅结束，检查点保存在内存注册表。
     */
    public void pause(String executionId) {
        executionControl.requestPause(executionId);
    }

    /**
     * 恢复编排：从暂停检查点（图 + 上下文 + 恢复节点）断点续跑。
     *
     * @return 续跑事件流（新订阅）
     * @throws AgentEngineException 无暂停检查点（未暂停或已恢复/进程重启丢失）
     */
    public Flux<AgentEvent> resume(String executionId) {
        Optional<ExecutionControl.Checkpoint> checkpoint = executionControl.takeCheckpoint(executionId);
        if (checkpoint.isEmpty()) {
            throw AgentEngineException.of("EXECUTION_NOT_PAUSED",
                    "执行无暂停检查点（未暂停、已恢复或进程重启丢失）: " + executionId);
        }
        ExecutionControl.Checkpoint cp = checkpoint.get();
        cp.context().putVariable(AgentEvent.VAR_RESUME_FROM_NODE, cp.resumeFromNodeId());
        executionControl.register(executionId);
        logResume(executionId, cp.resumeFromNodeId());
        return orchestrator.run(cp.graph(), cp.context());
    }

    private void logResume(String executionId, String nodeId) {
        // 独立方法便于追踪（保持类无 @Slf4j 依赖冲突）
        org.slf4j.LoggerFactory.getLogger(OrchestrationEngine.class)
                .info("编排断点续跑: executionId={}, resumeFromNode={}", executionId, nodeId);
    }

    /** 是否存在可恢复的暂停检查点 */
    public boolean isPausable(String executionId) {
        return executionControl.hasCheckpoint(executionId);
    }

    /**
     * 取消编排：运行中发协作信号（当前节点完成后优雅结束）；
     * 已暂停的执行直接丢弃检查点并注销。
     */
    public void cancel(String executionId) {
        if (executionControl.hasCheckpoint(executionId)) {
            executionControl.unregister(executionId);
        } else {
            executionControl.requestCancel(executionId);
        }
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

    public ExecutionControl getExecutionControl() {
        return executionControl;
    }
}
