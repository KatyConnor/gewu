package com.gewu.agent.engine.cognition;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 感知引擎 SPI - 将非结构化输入转化为结构化意图表示。
 * <p>使用方实现此接口对接意图分类、实体提取能力。
 * 框架提供 {@code NoOpPerceptionEngine}（passthrough 默认实现）。
 *
 * @since 1.0.0
 */
public interface PerceptionEngine {

    /**
     * 感知输入：将原始文本转化为结构化意图。
     *
     * @param rawInput 原始输入文本
     * @return 结构化意图
     */
    Intent perceive(String rawInput);

    /**
     * 意图表示 - 感知引擎的输出。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class Intent {
        /** 意图 ID */
        private String intentId;
        /** 意图类型: greeting/faq/task_query/code_gen/info_search/analysis/design/deploy/unknown */
        private String intentType;
        /** 置信度 [0, 1] */
        private double confidence;
        /** 提取的实体列表 */
        private java.util.List<String> entities;
        /** 实体数量 */
        private int entityCount;
        /** 是否需要多步执行 */
        private boolean requiresMultiStep;
        /** 是否需要澄清 */
        private boolean needClarification;
        /** 任务等级: L1/L2/L3（路由器填充） */
        private String taskLevel;
        /** 候选行动 */
        private java.util.List<String> candidateActions;

        public static Intent unknown(String rawInput) {
            return Intent.builder()
                    .intentId(java.util.UUID.randomUUID().toString())
                    .intentType("unknown")
                    .confidence(0.5)
                    .entities(java.util.List.of())
                    .entityCount(0)
                    .requiresMultiStep(false)
                    .needClarification(false)
                    .taskLevel("L2")
                    .candidateActions(java.util.List.of())
                    .build();
        }
    }
}