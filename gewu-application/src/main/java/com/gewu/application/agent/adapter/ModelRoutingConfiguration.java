package com.gewu.application.agent.adapter;

import com.gewu.infrastructure.llm.ModelRouter;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型路由配置 - 注册 {@link ModelRouter} bean（配置驱动模型特征注册表）。
 * <p>配置示例（application.yml）：
 * <pre>
 * gewu:
 *   llm:
 *     routing:
 *       default-model: glm-4-flash
 *       cheapest-model: glm-4-flash
 *       models:
 *         - model-id: glm-4-flash
 *           provider-code: zhipu
 *           tier: 1
 *           cost-per-1k-tokens: 0.001
 *           avg-latency-ms: 800
 *         - model-id: glm-4-plus
 *           provider-code: zhipu
 *           tier: 3
 *           cost-per-1k-tokens: 0.05
 *           avg-latency-ms: 2500
 * </pre>
 * 注册表为空时路由始终返回默认模型（未配置 default-model 时适配器返回 null 不干预）。
 *
 * @since 1.0.0
 */
@Configuration
@EnableConfigurationProperties(ModelRoutingConfiguration.ModelRoutingProperties.class)
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class ModelRoutingConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ModelRouter modelRouter(ModelRoutingProperties properties) {
        Map<String, ModelRouter.ModelFeatures> registry = new LinkedHashMap<>();
        for (ModelRoutingProperties.ModelEntry entry : properties.getModels()) {
            registry.put(entry.getModelId(), ModelRouter.ModelFeatures.builder()
                    .modelId(entry.getModelId())
                    .providerCode(entry.getProviderCode())
                    .tier(entry.getTier())
                    .costPer1kTokens(entry.getCostPer1kTokens())
                    .avgLatencyMs(entry.getAvgLatencyMs())
                    .local(entry.isLocal())
                    .build());
        }
        return new ModelRouter(registry, properties.getDefaultModel(), properties.getCheapestModel());
    }

    /**
     * 模型路由配置属性。
     */
    @Data
    @ConfigurationProperties(prefix = "gewu.llm.routing")
    public static class ModelRoutingProperties {
        /** 模型特征注册表 */
        private List<ModelEntry> models = new ArrayList<>();
        /** 默认模型（无候选/未配置注册表时使用） */
        private String defaultModel;
        /** 最便宜模型（预算耗尽/降级时使用，缺省同 default-model） */
        private String cheapestModel;

        /**
         * 模型特征条目。
         */
        @Data
        public static class ModelEntry {
            /** 模型 ID */
            private String modelId;
            /** 供应商代码 */
            private String providerCode;
            /** 模型等级：1(轻量) / 2(标准) / 3(强力) */
            private int tier = 2;
            /** 每 1k Token 成本（元） */
            private double costPer1kTokens;
            /** 平均延迟（毫秒） */
            private long avgLatencyMs;
            /** 是否本地部署（隐私安全） */
            private boolean local;
        }
    }
}
