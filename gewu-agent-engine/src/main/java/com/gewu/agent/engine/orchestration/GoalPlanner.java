package com.gewu.agent.engine.orchestration;

import com.gewu.agent.engine.orchestration.model.AutonomousGoal;
import com.gewu.agent.engine.orchestration.model.ExecutionGraph;
import com.gewu.agent.engine.orchestration.model.GraphType;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import com.gewu.agent.engine.orchestration.model.OrchestrationMode;
import com.gewu.agent.engine.orchestration.model.PlanGraph;

import java.util.Map;

/**
 * 目标分解器 SPI - 将高层目标分解为编排图（双图解耦）。
 * <p>分解策略（三级路由，复用经验库匹配 -> LLM 规划 -> Critic 验证）：
 * <ol>
 *   <li>经验库匹配：命中则复用历史计划（微调）</li>
 *   <li>LLM Planner 规划：生成计划图草案</li>
 *   <li>Critic 验证：不通过则回到规划（限 N 轮）</li>
 * </ol>
 * <p>双图映射：{@link #plan} 产出计划图（逻辑层，人类可理解），
 * {@link ExecutionGraph#fromPlanGraph} 映射为执行图（技术层，模型/工具/沙箱配置），
 * 再包装为编排图驱动执行。技术实现变化时只需调整执行图映射，计划图保持稳定。
 *
 * @since 1.0.0
 */
public interface GoalPlanner {

    /**
     * 目标规划：高层目标 -> 计划图（逻辑层，人类可理解的任务步骤描述）。
     */
    PlanGraph plan(AutonomousGoal goal, OrchestrationContext ctx);

    /**
     * 目标分解：高层目标 -> 编排图。
     * <p>默认实现走双图映射：计划图（{@link #plan}）-> 执行图
     * （{@link ExecutionGraph#fromPlanGraph}，无策略注入）-> 编排图。
     * 使用方可覆盖以自定义编排图生成（如注入策略配置、推荐编排模式）。
     */
    default OrchestrationGraph decompose(AutonomousGoal goal, OrchestrationContext ctx) {
        PlanGraph plan = plan(goal, ctx);
        ExecutionGraph executionGraph = ExecutionGraph.fromPlanGraph(plan, null);
        return OrchestrationGraph.builder()
                .graphId(executionGraph.getExecutionGraphId())
                .name(plan.getGoal())
                .type(GraphType.GOAL_DECOMPOSED)
                .mode(OrchestrationMode.PIPELINE)
                .nodes(executionGraph.getNodes())
                .edges(executionGraph.getEdges())
                .variables(Map.of("input", plan.getGoal() != null ? plan.getGoal() : ""))
                .rootGoalId(goal.getGoalId())
                .build();
    }
}
