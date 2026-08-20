package com.gewu.application.agent.adapter;

import com.gewu.agent.engine.spi.ModelSelector;
import com.gewu.infrastructure.llm.ModelRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 模型选择适配器 - 桥接引擎 {@link ModelSelector} SPI 与基础设施层 {@link ModelRouter}。
 * <p>按复杂度/隐私/延迟/预算路由最优模型（优先级：预算 -> 隐私 -> 复杂度 -> 延迟 -> 最便宜），
 * 返回模型名与供应商代码。路由失败返回 null（不干预，保持调用方默认模型）。
 * <p>启用条件：{@code agent.engine.adapter.enabled=true} 且 {@code gewu.llm.routing.models} 已配置模型特征。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class ModelSelectorAdapter implements ModelSelector {

    private final ModelRouter modelRouter;

    @Override
    public ModelSelection select(String taskDescription, int complexity, String privacyLevel,
                                 long latencyRequirementMs, long budgetRemainingTokens) {
        try {
            ModelRouter.ModelChoice choice = modelRouter.route(
                    taskDescription, complexity, privacyLevel,
                    latencyRequirementMs, budgetRemainingTokens);
            if (choice == null || choice.getModelId() == null || choice.getModelId().isBlank()) {
                return null;
            }
            ModelRouter.ModelFeatures features = modelRouter.features(choice.getModelId());
            return ModelSelection.builder()
                    .modelName(choice.getModelId())
                    .modelProvider(features != null ? features.getProviderCode() : null)
                    .reason(choice.getReason())
                    .build();
        } catch (Exception e) {
            log.warn("ModelSelectorAdapter 路由失败，回退默认模型: {}", e.getMessage());
            return null;
        }
    }
}
