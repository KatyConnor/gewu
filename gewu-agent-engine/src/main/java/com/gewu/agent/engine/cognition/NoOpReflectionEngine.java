package com.gewu.agent.engine.cognition;

/**
 * {@link ReflectionEngine} 的 NoOp 默认实现 - 不执行反思。
 *
 * @since 1.0.0
 */
public class NoOpReflectionEngine implements ReflectionEngine {

    @Override
    public String reflect(String executionId, String result, String goal) {
        return "NoOp: 无反思";
    }
}