package com.gewu.agent.engine.cognition;

import java.util.List;

/**
 * {@link ReasoningKernel} 的 NoOp 默认实现 - 不提供认知推理能力。
 *
 * @since 1.0.0
 */
public class NoOpReasoningKernel implements ReasoningKernel {

    @Override
    public ReasoningResult plan(String task, String context) {
        return ReasoningResult.builder().type("PLAN").subtasks(List.of(task)).build();
    }

    @Override
    public String routeSolver(String task) {
        return "DIRECT_ANSWER";
    }

    @Override
    public ReasoningResult critique(String output, List<String> acceptances) {
        return ReasoningResult.builder().type("CRITIC").accepted(true).score(1.0)
                .verdict("NoOp: 默认通过").build();
    }
}