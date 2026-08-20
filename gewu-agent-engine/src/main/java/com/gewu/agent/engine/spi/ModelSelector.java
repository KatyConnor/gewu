package com.gewu.agent.engine.spi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型选择器 SPI - 按任务复杂度 / 隐私 / 延迟 / 预算动态选择最优 LLM 模型。
 * <p>引擎在 LLM 调用前咨询本 SPI（调用方未显式指定模型时）；
 * 返回 null 表示不干预，使用调用方/Agent 默认模型。
 * 使用方实现本接口桥接模型路由服务（如 ModelRouter），实现按复杂度分级调用与成本优化。
 *
 * @since 1.0.0
 */
public interface ModelSelector {

    /**
     * 选择最优模型。
     *
     * @param taskDescription      任务描述
     * @param complexity           任务复杂度（1-10，来自 ComplexityRouter）
     * @param privacyLevel         隐私级别：low / medium / high（可为 null）
     * @param latencyRequirementMs 延迟要求（毫秒，0 表示无要求）
     * @param budgetRemainingTokens 剩余 Token 预算
     * @return 模型选择（null 表示不干预）
     */
    ModelSelection select(String taskDescription, int complexity, String privacyLevel,
                          long latencyRequirementMs, long budgetRemainingTokens);

    /**
     * 模型选择结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class ModelSelection {
        /** 供应商代码（null 表示保持当前供应商） */
        private String modelProvider;
        /** 模型 ID */
        private String modelName;
        /** 选择原因（可观测） */
        private String reason;
    }
}
