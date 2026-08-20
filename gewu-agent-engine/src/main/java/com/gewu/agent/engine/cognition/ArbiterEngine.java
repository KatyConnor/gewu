package com.gewu.agent.engine.cognition;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 仲裁引擎 SPI - 对争议进行最终裁决，选择最优候选方案。
 * <p>使用方实现此接口对接 LLM 多采样仲裁、跨厂商对抗仲裁等能力。
 * 框架提供 {@code NoOpArbiterEngine}（取第一个候选方案的默认实现）。
 *
 * @since 1.0.0
 */
public interface ArbiterEngine {

    /**
     * 仲裁：从候选方案中选择最优。
     *
     * @param context    仲裁上下文（问题描述/冲突描述）
     * @param candidates 候选方案列表
     * @return 仲裁结果（含胜出索引与理由）
     */
    ArbitrationResult arbitrate(String context, List<String> candidates);

    /**
     * 仲裁结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class ArbitrationResult {
        /** 决策说明 */
        private String decision;
        /** 推理过程 */
        private String reasoning;
        /** 置信度 [0, 1] */
        private double confidence;
        /** 证据链 */
        private String evidence;
        /** 胜出候选索引 */
        private int winnerIndex;

        public static ArbitrationResult of(int winnerIndex, String decision, double confidence, String reasoning) {
            return ArbitrationResult.builder()
                    .winnerIndex(winnerIndex)
                    .decision(decision)
                    .confidence(confidence)
                    .reasoning(reasoning)
                    .evidence(reasoning)
                    .build();
        }
    }
}