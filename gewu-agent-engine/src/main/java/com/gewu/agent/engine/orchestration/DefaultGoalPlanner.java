package com.gewu.agent.engine.orchestration;

import com.gewu.agent.engine.orchestration.model.AutonomousGoal;
import com.gewu.agent.engine.orchestration.model.ExecutionGraph;
import com.gewu.agent.engine.orchestration.model.GraphNode;
import com.gewu.agent.engine.orchestration.model.GraphType;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import com.gewu.agent.engine.orchestration.model.OrchestrationMode;
import com.gewu.agent.engine.orchestration.model.PlanGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link GoalPlanner} 默认实现 - 简单模式：单步骤计划图经双图映射生成单 AGENT 节点图。
 * <p>decompose 链路：plan()（计划图，逻辑层）-> {@link ExecutionGraph#fromPlanGraph}
 * （执行图，技术层）-> 编排图（补齐 inputs/config 运行时语义）。
 * <p>使用方可覆盖 plan() 提供真实 LLM 分解 / 经验库匹配 / Critic 验证的完整三级路由实现。
 *
 * @since 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultGoalPlanner implements GoalPlanner {

    @Override
    public PlanGraph plan(AutonomousGoal goal, OrchestrationContext ctx) {
        return PlanGraph.builder()
                .planId("plan-" + UUID.randomUUID())
                .goal(goal.getDescription())
                .createdBy("DefaultGoalPlanner")
                .steps(List.of(PlanGraph.PlanStep.builder()
                        .stepId("n1")
                        .description(goal.getDescription())
                        .dependencies(List.of())
                        .estimatedComplexity(1)
                        .build()))
                .build();
    }

    @Override
    public OrchestrationGraph decompose(AutonomousGoal goal, OrchestrationContext ctx) {
        OrchestrationMode mode = recommendMode(goal);
        log.info("目标分解: goal={}, 推荐模式={}", goal.getDescription(), mode);

        // 双图映射：计划图 -> 执行图 -> 编排图
        PlanGraph plan = plan(goal, ctx);
        ExecutionGraph executionGraph = ExecutionGraph.fromPlanGraph(plan, null);

        List<GraphNode> nodes = executionGraph.getNodes();
        if (!nodes.isEmpty()) {
            // 保持运行时语义：单 Agent 节点补齐输入映射与迭代配置
            GraphNode node = nodes.get(0);
            node.setConfig(Map.of("maxIterations", goal.getMaxIterations()));
            node.setInputs(Map.of("goal", goal.getDescription()));
        }

        return OrchestrationGraph.builder()
                .graphId(UUID.randomUUID().toString())
                .name(goal.getDescription())
                .type(GraphType.GOAL_DECOMPOSED)
                .mode(mode)
                .nodes(nodes)
                .edges(executionGraph.getEdges())
                .variables(Map.of("input", goal.getDescription()))
                .rootGoalId(goal.getGoalId())
                .build();
    }

    /**
     * 按目标特征推荐编排模式。
     * <ul>
     *   <li>FEATURE + 流程明确 -> PIPELINE</li>
     *   <li>RESEARCH / 边界不清 -> SWARM 或 SUPERVISOR</li>
     *   <li>技术选型 / 架构决策 -> DEBATE</li>
     * </ul>
     */
    private OrchestrationMode recommendMode(AutonomousGoal goal) {
        String type = goal.getType();
        if (type == null) {
            return OrchestrationMode.PIPELINE;
        }
        return switch (type) {
            case "FEATURE", "BUGFIX" -> OrchestrationMode.PIPELINE;
            case "REFACTOR", "OPS" -> OrchestrationMode.SUPERVISOR;
            case "RESEARCH" -> OrchestrationMode.SWARM;
            default -> OrchestrationMode.PIPELINE;
        };
    }
}
