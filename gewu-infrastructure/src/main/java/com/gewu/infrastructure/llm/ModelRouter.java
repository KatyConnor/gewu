package com.gewu.infrastructure.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 模型路由器 - 根据任务特征动态选择最优 LLM 模型。
 * <p>路由优先级：预算 → 隐私 → 复杂度 → 延迟 → 最便宜。
 * <p>模型特征通过注册表配置（因 ModelProvider 实体暂无成本/延迟字段）。
 *
 * @since 1.0.0
 */
@Slf4j
public class ModelRouter {

    /** 模型特征注册表（modelId -> features） */
    private final Map<String, ModelFeatures> modelRegistry;

    /** 默认模型（注册表中无匹配时使用） */
    private final String defaultModel;

    /** 低成本模型（预算紧张/降级时使用） */
    private final String cheapestModel;

    public ModelRouter(Map<String, ModelFeatures> modelRegistry, String defaultModel, String cheapestModel) {
        this.modelRegistry = modelRegistry;
        this.defaultModel = defaultModel;
        this.cheapestModel = cheapestModel != null ? cheapestModel : defaultModel;
    }

    /**
     * 路由选择最优模型。
     *
     * @param taskDescription 任务描述
     * @param complexity      任务复杂度（1-10）
     * @param privacyLevel    隐私级别：low / medium / high
     * @param latencyMs       延迟要求（毫秒，0 表示无要求）
     * @param budgetRemaining 剩余预算（Token）
     * @return 模型选择
     */
    public ModelChoice route(String taskDescription, int complexity, String privacyLevel,
                             long latencyMs, long budgetRemaining) {
        // 1. 预算检查（最高优先级）
        if (budgetRemaining <= 0) {
            log.debug("ModelRouter: 预算耗尽，使用最便宜模型: {}", cheapestModel);
            return ModelChoice.of(cheapestModel, "budget_exhausted");
        }

        // 2. 隐私要求检查
        if ("high".equalsIgnoreCase(privacyLevel)) {
            String localModel = findLocalModel();
            if (localModel != null) {
                log.debug("ModelRouter: 隐私要求高，使用本地模型: {}", localModel);
                return ModelChoice.of(localModel, "privacy_requirement");
            }
        }

        // 3. 按复杂度筛选候选
        List<ModelFeatures> candidates = new ArrayList<>(modelRegistry.values());
        int targetTier = complexityToTier(complexity);
        candidates.removeIf(m -> m.getTier() > targetTier + 1);

        // 4. 按延迟要求筛选
        if (latencyMs > 0) {
            candidates.removeIf(m -> m.getAvgLatencyMs() > latencyMs);
        }

        // 5. 按预算筛选（排除超出预算的模型）
        candidates.removeIf(m -> m.getCostPer1kTokens() > (double) budgetRemaining / 1000);

        if (candidates.isEmpty()) {
            log.debug("ModelRouter: 无候选模型，使用默认模型: {}", defaultModel);
            return ModelChoice.of(defaultModel, "no_candidates");
        }

        // 6. 在候选中选择成本最低的
        ModelFeatures optimal = candidates.stream()
                .min(Comparator.comparingDouble(ModelFeatures::getCostPer1kTokens))
                .orElse(modelRegistry.get(defaultModel));

        String reason = String.format("complexity=%d(tier=%d), latency_req=%dms, cost=%.4f/1k",
                complexity, targetTier, latencyMs, optimal.getCostPer1kTokens());
        log.debug("ModelRouter: 选择模型: {}, {}", optimal.getModelId(), reason);
        return ModelChoice.of(optimal.getModelId(), reason);
    }

    /**
     * 预算降级：返回最便宜的可用模型。
     */
    public ModelChoice degrade() {
        return ModelChoice.of(cheapestModel, "budget_degrade");
    }

    /**
     * 查询模型特征（供应商代码 / 等级 / 成本），供桥接层解析 provider。
     *
     * @param modelId 模型 ID
     * @return 模型特征（未注册返回 null）
     */
    public ModelFeatures features(String modelId) {
        return modelRegistry.get(modelId);
    }

    private int complexityToTier(int complexity) {
        if (complexity <= 2) return 1;  // 简单任务 → Tier 1 模型
        if (complexity <= 5) return 2;  // 中等任务 → Tier 2 模型
        return 3;                       // 复杂任务 → Tier 3 模型
    }

    private String findLocalModel() {
        return modelRegistry.values().stream()
                .filter(ModelFeatures::isLocal)
                .map(ModelFeatures::getModelId)
                .findFirst()
                .orElse(null);
    }

    // ===== DTOs =====

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelFeatures {
        /** 模型 ID */
        private String modelId;
        /** 供应商代码 */
        private String providerCode;
        /** 模型等级：1(轻量) / 2(标准) / 3(强力) */
        private int tier;
        /** 每 1k Token 成本（元） */
        private double costPer1kTokens;
        /** 平均延迟（毫秒） */
        private long avgLatencyMs;
        /** 是否本地部署（隐私安全） */
        private boolean local;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelChoice {
        /** 选择的模型 ID */
        private String modelId;
        /** 选择原因 */
        private String reason;

        public static ModelChoice of(String modelId, String reason) {
            return new ModelChoice(modelId, reason);
        }
    }
}