package com.gewu.agent.engine.cognition;

/**
 * {@link EvolutionHook} 的 NoOp 默认实现 - 不触发进化闭环。
 *
 * @since 1.0.0
 */
public class NoOpEvolutionHook implements EvolutionHook {

    @Override
    public void onNodeComplete(String nodeId, String nodeResult) {
        // NoOp
    }

    @Override
    public String onGraphComplete(String graphId, String result, String reflection) {
        return null;
    }
}