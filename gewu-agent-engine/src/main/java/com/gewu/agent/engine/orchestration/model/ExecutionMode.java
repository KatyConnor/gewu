package com.gewu.agent.engine.orchestration.model;

/**
 * Agent 运行时执行模式。
 *
 * @since 1.0.0
 */
public enum ExecutionMode {
    /** Thought->Action->Observation 循环（默认） */
    REACT,
    /** 先规划任务列表再逐步执行 */
    PLAN_EXECUTE,
    /** 执行后反思->改进->重试 */
    REFLEXION,
    /** 多工具并行调用 */
    TOOL_PARALLEL
}
