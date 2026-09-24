package com.gewu.application.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.domain.ai.ModelConfig;
import com.gewu.infrastructure.mapper.ModelConfigMapper;
import com.gewu.agent.engine.spi.CostCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 基于模型定价表的运行时成本计算器（优化2）。
 * <p>数据源：{@code model_config.price_per1k_input / price_per1k_output}（元/1K token，
 * V36 迁移引入）；计价口径与 {@link CostAccountingService} 一致：
 * {@code input/1000×priceIn + output/1000×priceOut}。
 * <p>引擎在 Token 记账时实时调用：配置了 {@code agent.engine.budget.cost-budget}
 * （金额上限）时，真实金额参与运行时熔断。查无定价/查询失败时回退统一假单价
 * （{@link CostCalculator#FALLBACK_PRICE_PER_TOKEN}），保证金额熔断在无定价数据时
 * 仍可用（等价于 token 维双份护栏）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelPricingCostCalculator implements CostCalculator {

    private final ModelConfigMapper modelConfigMapper;

    @Override
    public double cost(String modelId, long promptTokens, long completionTokens) {
        BigDecimal priceIn = null;
        BigDecimal priceOut = null;
        int unit = 1000;
        if (modelId != null && !modelId.isBlank()) {
            try {
                List<ModelConfig> configs = modelConfigMapper.selectList(
                        new LambdaQueryWrapper<ModelConfig>()
                                .eq(ModelConfig::getModelId, modelId)
                                .eq(ModelConfig::getStatus, 1)
                                .last("LIMIT 1"));
                if (!configs.isEmpty()) {
                    ModelConfig config = configs.get(0);
                    priceIn = config.getPricePer1kInput();
                    priceOut = config.getPricePer1kOutput();
                    if (config.getPriceUnitTokens() != null && config.getPriceUnitTokens() > 0) {
                        unit = config.getPriceUnitTokens();
                    }
                }
            } catch (Exception e) {
                log.debug("模型单价查询失败（回退统一估算价）: model={}, cause={}", modelId, e.getMessage());
            }
        }
        if (priceIn == null && priceOut == null) {
            // 无定价数据：回退统一假单价（与引擎缺省实现同口径，熔断仍可用）
            return (promptTokens + completionTokens) * FALLBACK_PRICE_PER_TOKEN;
        }
        BigDecimal in = BigDecimal.valueOf(promptTokens)
                .multiply(priceIn != null ? priceIn : BigDecimal.ZERO)
                .divide(BigDecimal.valueOf(unit), 6, RoundingMode.HALF_UP);
        BigDecimal out = BigDecimal.valueOf(completionTokens)
                .multiply(priceOut != null ? priceOut : BigDecimal.ZERO)
                .divide(BigDecimal.valueOf(unit), 6, RoundingMode.HALF_UP);
        return in.add(out).doubleValue();
    }
}
