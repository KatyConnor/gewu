package com.gewu.agent.engine.cognition;

/**
 * 反思引擎 SPI - 执行后反思，产出改进方案。
 * <p>使用方实现此接口对接反思 Agent（如 Wenshi ReflectionAgent），
 * 评估整体执行质量并输出改进建议，供 ReflexionRuntime 重做或 GoalPlanner 重规划使用。
 *
 * @since 1.0.0
 */
public interface ReflectionEngine {

    /**
     * 反思执行结果。
     *
     * @param executionId 执行实例 ID
     * @param result      执行产出
     * @param goal        目标描述
     * @return 反思结论（含改进建议）
     */
    String reflect(String executionId, String result, String goal);

    /**
     * 基于反思重规划。
     *
     * @param goal       原目标
     * @param reflection 反思结论
     * @return 改进后的任务描述
     */
    default String replan(String goal, String reflection) {
        return goal;
    }
}