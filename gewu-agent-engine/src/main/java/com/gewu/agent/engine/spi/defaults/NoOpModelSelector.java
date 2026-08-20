package com.gewu.agent.engine.spi.defaults;

import com.gewu.agent.engine.spi.ModelSelector;

/**
 * {@link ModelSelector} 默认空实现 - 不干预模型选择，使用调用方/Agent 默认模型。
 *
 * @since 1.0.0
 */
public class NoOpModelSelector implements ModelSelector {

    @Override
    public ModelSelection select(String taskDescription, int complexity, String privacyLevel,
                                 long latencyRequirementMs, long budgetRemainingTokens) {
        return null;
    }
}
