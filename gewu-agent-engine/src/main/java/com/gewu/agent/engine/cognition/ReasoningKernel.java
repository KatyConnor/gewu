package com.gewu.agent.engine.cognition;

/**
 * 推理内核 SPI - 提供 Planner / Solver / Critic 三大认知能力。
 * <p>使用方实现此接口对接认知引擎（如 Wenshi 推理层）。
 * 框架提供 {@code NoOpReasoningKernel}（空实现），编排层不依赖认知能力也可运行。
 * <p>三大能力：
 * <ul>
 *   <li>Planner - 将高层任务拆解为子任务列表</li>
 *   <li>Solver - 根据任务路由选择求解策略（工具执行 / 代码生成 / 知识检索）</li>
 *   <li>Critic - 评估执行结果是否达标，输出评分与改进建议</li>
 * </ul>
 *
 * @since 1.0.0
 */
public interface ReasoningKernel {

    /**
     * 规划：任务 -> 子任务列表。
     */
    ReasoningResult plan(String task, String context);

    /**
     * 求解策略路由。
     *
     * @return 策略标识：TOOL_EXECUTION / CODE_GENERATION / KNOWLEDGE_RETRIEVAL / DIRECT_ANSWER
     */
    String routeSolver(String task);

    /**
     * 评估：结果是否达标。
     *
     * @param output      执行产出
     * @param acceptances 验收标准列表
     * @return 评估结果（含评分与 accepted 标志）
     */
    ReasoningResult critique(String output, java.util.List<String> acceptances);
}