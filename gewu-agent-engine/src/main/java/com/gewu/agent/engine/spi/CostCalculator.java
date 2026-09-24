package com.gewu.agent.engine.spi;

/**
 * 成本计算器 SPI - 按"模型 × 输入/输出 token 拆分"计算真实调用成本（元）。
 * <p>引擎在 Token 记账时同步调用，将结果累加进 {@code BudgetContext.costConsumed}；
 * 配置了金额上限（{@code agent.engine.budget.cost-budget}）时参与运行时熔断。
 * <p>装配模式与 {@link TraceService}/{@link MetricService} 一致：AutoConfiguration
 * 提供统一假单价默认实现，宿主应用提供基于模型定价表的实现时自动覆盖。
 *
 * @since 1.0.0
 */
public interface CostCalculator {

    /**
     * 计算一次 LLM 调用的成本。
     *
     * @param modelId          模型标识（如 gpt-4o、qwen-plus）
     * @param promptTokens     输入（提示）token 数
     * @param completionTokens 输出（生成）token 数
     * @return 成本（元）；无法计价时返回统一估算值，不得抛出异常
     */
    double cost(String modelId, long promptTokens, long completionTokens);

    /** 统一假单价（元/token）：与历史行为保持一致的兜底估算 */
    double FALLBACK_PRICE_PER_TOKEN = 0.00001;
}
