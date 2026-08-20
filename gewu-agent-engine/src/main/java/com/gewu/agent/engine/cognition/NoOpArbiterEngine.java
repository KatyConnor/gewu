package com.gewu.agent.engine.cognition;

import java.util.List;

/**
 * {@link ArbiterEngine} 的 NoOp 默认实现 - 取第一个候选方案。
 *
 * @since 1.0.0
 */
public class NoOpArbiterEngine implements ArbiterEngine {

    @Override
    public ArbitrationResult arbitrate(String context, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return ArbitrationResult.of(0, "无候选方案", 0.5, "NoOp: 无候选");
        }
        return ArbitrationResult.of(0, "NoOp: 取第一个候选", 0.5, "默认仲裁");
    }
}