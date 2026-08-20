package com.gewu.agent.engine.cognition;

/**
 * {@link PerceptionEngine} 的 NoOp 默认实现 - passthrough，不执行感知。
 *
 * @since 1.0.0
 */
public class NoOpPerceptionEngine implements PerceptionEngine {

    @Override
    public Intent perceive(String rawInput) {
        return Intent.unknown(rawInput);
    }
}