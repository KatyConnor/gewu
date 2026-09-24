package com.gewu.agent.engine.budget;

import lombok.extern.slf4j.Slf4j;

/**
 * 预算控制器 - 四维预算控制（Token/时间/成本/轮次）。
 * <p>核心策略：收益递减即收敛，耗尽即熔断。
 * <ul>
 *   <li>利用率 < 70% -> {@link BudgetStatus#NORMAL}（正常执行）</li>
 *   <li>利用率 70%-90% -> {@link BudgetStatus#ALERT}（告警，优先用 System 1）</li>
 *   <li>利用率 90%-100% -> {@link BudgetStatus#DEGRADE}（强制降级到便宜模型）</li>
 *   <li>利用率 ≥ 100% -> {@link BudgetStatus#BLOCK}（终止执行）</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Slf4j
public class BudgetController {

    private static final double ALERT_THRESHOLD = 0.70;
    private static final double DEGRADE_THRESHOLD = 0.90;
    private static final double BLOCK_THRESHOLD = 1.00;

    private final long defaultTokenBudget;
    private final long defaultTimeBudgetMs;
    private final int defaultMaxRounds;
    private final Quotas quotas;
    /** 告警/降级阈值（可配）；未显式配置时与历史常量一致 */
    private final double alertThreshold;
    private final double degradeThreshold;
    /** 单次执行金额上限（元）；>0 时启用成本维 BLOCK，≤0 时成本仅记账观测 */
    private final double configuredCostBudgetYuan;
    /** 成本计算器（模型×输入/输出拆分计价）；null 时使用统一假单价兜底 */
    private final com.gewu.agent.engine.spi.CostCalculator costCalculator;

    /**
     * L1/L3 等级配额参数（原硬编码倍率，现由 agent.engine.budget.* 配置）。
     */
    @lombok.Data
    public static class Quotas {
        /** L1 token 预算除数 */
        private int l1TokenDivisor = 5;
        /** L1 时间预算（毫秒） */
        private long l1TimeBudgetMs = 30_000;
        /** L1 最大轮次 */
        private int l1MaxRounds = 3;
        /** L3 token 预算倍数 */
        private int l3TokenMultiplier = 3;
        /** L3 时间预算倍数 */
        private int l3TimeMultiplier = 4;
        /** L3 轮次倍数 */
        private int l3RoundsMultiplier = 2;
    }

    public BudgetController(long defaultTokenBudget, long defaultTimeBudgetMs, int defaultMaxRounds) {
        this(defaultTokenBudget, defaultTimeBudgetMs, defaultMaxRounds, new Quotas());
    }

    public BudgetController(long defaultTokenBudget, long defaultTimeBudgetMs, int defaultMaxRounds,
                            Quotas quotas) {
        this(defaultTokenBudget, defaultTimeBudgetMs, defaultMaxRounds, quotas, 0, null);
    }

    /**
     * 完整构造：金额上限与成本计算器（成本真实计价 + 可选金额熔断）。
     *
     * @param costBudgetYuan 单次执行金额上限（元）；>0 启用成本维 BLOCK，L1/L3 按 token 同倍率缩放
     * @param costCalculator 成本计算器；null 时使用统一假单价（与历史行为一致）
     */
    public BudgetController(long defaultTokenBudget, long defaultTimeBudgetMs, int defaultMaxRounds,
                            Quotas quotas, double costBudgetYuan,
                            com.gewu.agent.engine.spi.CostCalculator costCalculator) {
        this(defaultTokenBudget, defaultTimeBudgetMs, defaultMaxRounds, quotas, costBudgetYuan,
                costCalculator, ALERT_THRESHOLD, DEGRADE_THRESHOLD);
    }

    /**
     * 完整构造（含可配阈值）：告警/降级阈值由 agent.engine.budget.* 注入。
     *
     * @param alertThreshold   告警阈值（利用率 ≥ 此值发 budget_warning），默认 0.70
     * @param degradeThreshold 降级告警阈值，默认 0.90
     */
    public BudgetController(long defaultTokenBudget, long defaultTimeBudgetMs, int defaultMaxRounds,
                            Quotas quotas, double costBudgetYuan,
                            com.gewu.agent.engine.spi.CostCalculator costCalculator,
                            double alertThreshold, double degradeThreshold) {
        this.defaultTokenBudget = defaultTokenBudget;
        this.defaultTimeBudgetMs = defaultTimeBudgetMs;
        this.defaultMaxRounds = defaultMaxRounds;
        this.quotas = quotas != null ? quotas : new Quotas();
        this.configuredCostBudgetYuan = costBudgetYuan;
        this.costCalculator = costCalculator;
        this.alertThreshold = alertThreshold;
        this.degradeThreshold = degradeThreshold;
    }

    /**
     * 根据任务等级创建预算上下文。
     *
     * @param taskLevel 任务等级 L1/L2/L3（null 时默认 L2）
     * @return 预算上下文
     */
    public BudgetContext createBudget(String taskLevel) {
        long tokenBudget = defaultTokenBudget;
        long timeBudgetMs = defaultTimeBudgetMs;
        int maxRounds = defaultMaxRounds;
        // 金额上限：配置值 >0 时按 token 同倍率缩放并启用成本维熔断；
        // 未配置（≤0）时维持旧派生值（仅观测，不参与 check 判定）
        double costBudget;
        if (configuredCostBudgetYuan > 0) {
            costBudget = configuredCostBudgetYuan;
            if ("L1".equals(taskLevel)) {
                costBudget = configuredCostBudgetYuan / quotas.getL1TokenDivisor();
            } else if ("L3".equals(taskLevel)) {
                costBudget = configuredCostBudgetYuan * quotas.getL3TokenMultiplier();
            }
        } else {
            costBudget = defaultTokenBudget * 0.00001;
        }

        if ("L1".equals(taskLevel)) {
            tokenBudget = defaultTokenBudget / quotas.getL1TokenDivisor();
            timeBudgetMs = quotas.getL1TimeBudgetMs();
            maxRounds = quotas.getL1MaxRounds();
        } else if ("L3".equals(taskLevel)) {
            tokenBudget = defaultTokenBudget * quotas.getL3TokenMultiplier();
            timeBudgetMs = defaultTimeBudgetMs * quotas.getL3TimeMultiplier();
            maxRounds = defaultMaxRounds * quotas.getL3RoundsMultiplier();
        }

        return BudgetContext.builder()
                .taskLevel(taskLevel != null ? taskLevel : "L2")
                .tokenBudget(tokenBudget)
                .tokenConsumed(0)
                .timeBudgetMs(timeBudgetMs)
                .startTimeMs(System.currentTimeMillis())
                .costBudget(tokenBudget * 0.00001)
                .costConsumed(0)
                .maxRounds(maxRounds)
                .currentRound(0)
                .build();
    }

    /**
     * 记录 Token 与成本消耗。
     */
    public void consume(BudgetContext ctx, long tokens, double cost) {
        if (ctx == null) return;
        ctx.setTokenConsumed(ctx.getTokenConsumed() + tokens);
        ctx.setCostConsumed(ctx.getCostConsumed() + cost);
        ctx.setCurrentRound(ctx.getCurrentRound() + 1);
        log.debug("BudgetController.consume: tokens={}, cost={}, totalTokens={}, round={}",
                tokens, cost, ctx.getTokenConsumed(), ctx.getCurrentRound());
    }

    /**
     * 按模型与输入/输出 token 拆分计算成本（元）。
     * <p>未装配计算器时回退统一假单价（与历史行为一致）；实现方保证不抛异常。
     */
    public double calculateCost(String modelId, long promptTokens, long completionTokens) {
        if (costCalculator != null) {
            return costCalculator.cost(modelId, promptTokens, completionTokens);
        }
        return (promptTokens + completionTokens)
                * com.gewu.agent.engine.spi.CostCalculator.FALLBACK_PRICE_PER_TOKEN;
    }

    /**
     * 检查预算状态。
     * <p>阻断语义（S9 重构 + 配额开关）：Token 超限触发 BLOCK 受 {@code blockEnabled}
     * 开关控制（false=仅告警不熔断，用户偏好决定）——它们直接度量工作量与循环失控；
     * 轮次超限与成本超限不受开关控制（失控防线）。时间维度降级为告警信号（不阻断）：
     * 墙钟会误杀健康的马拉松任务，真正的卡死由模型层空闲看门狗负责。
     * 成本维仅在显式配置金额上限（cost-budget > 0）时参与 BLOCK。
     * DEGRADE/ALERT 信号仍取 token/时间两者最大值。
     */
    public BudgetStatus check(BudgetContext ctx) {
        if (ctx == null) return BudgetStatus.NORMAL;

        double tokenUtil = ctx.getTokenUtilization();
        double timeUtil = ctx.getTimeUtilization();
        double maxUtil = Math.max(tokenUtil, timeUtil);

        boolean tokenBlock = ctx.getBlockEnabled() == null || ctx.getBlockEnabled();
        if ((tokenBlock && tokenUtil >= BLOCK_THRESHOLD)
                || ctx.getCurrentRound() >= ctx.getMaxRounds()
                || (configuredCostBudgetYuan > 0 && ctx.getCostUtilization() >= BLOCK_THRESHOLD)) {
            return BudgetStatus.BLOCK;
        } else if (maxUtil >= degradeThreshold) {
            return BudgetStatus.DEGRADE;
        } else if (maxUtil >= alertThreshold) {
            return BudgetStatus.ALERT;
        }
        return BudgetStatus.NORMAL;
    }

    /**
     * 时间预算滚动续期（S9 方案A）：时间到达上限但 Token/轮次仍健康时，
     * 续一个新的时间片而不是终止——持续健康推进的任务永不因墙钟被杀。
     *
     * @return true 表示发生了续期（调用方应发预算告警事件）
     */
    public boolean renewTimeBudget(BudgetContext ctx) {
        if (ctx == null || ctx.getTimeBudgetMs() <= 0) {
            return false;
        }
        long elapsed = ctx.getElapsedMs();
        if (elapsed < ctx.getTimeBudgetMs()) {
            return false;
        }
        long slice = Math.max(ctx.getTimeBudgetMs() / 2, 30_000L);
        ctx.setTimeBudgetMs(elapsed + slice);
        ctx.setTimeRenewals(ctx.getTimeRenewals() + 1);
        // 新续期周期重置告警去重水位：允许新周期内再各发一次升级告警
        ctx.setLastAlertLevel(0);
        log.info("时间预算滚动续期: elapsed={}ms, newBudget={}ms, renewals={} (任务仍在健康推进)",
                elapsed, ctx.getTimeBudgetMs(), ctx.getTimeRenewals());
        return true;
    }

    /**
     * 轮次预算滚动扩容：轮次到达上限但无死循环迹象时，扩 50%（≥10 轮）而非强制总结——
     * 与时间滚动续期同哲学：持续健康推进的任务不因轮次闸门被误杀；
     * 扩容次数受调用方 rounds-renew-max 约束，绝对上限 = 基线×(1+扩容比例×次数)。
     *
     * @return true 表示发生了扩容（调用方应发预算告警事件）
     */
    public boolean renewRounds(BudgetContext ctx) {
        if (ctx == null || ctx.getMaxRounds() <= 0) {
            return false;
        }
        int add = Math.max(ctx.getMaxRounds() / 2, 10);
        ctx.setMaxRounds(ctx.getMaxRounds() + add);
        ctx.setRoundsRenewed(ctx.getRoundsRenewed() + 1);
        ctx.setLastAlertLevel(0);
        log.info("轮次预算滚动扩容: maxRounds={}, renewals={} (无死循环迹象，任务仍在推进)",
                ctx.getMaxRounds(), ctx.getRoundsRenewed());
        return true;
    }

    /**
     * 是否应该终止执行。
     */
    public boolean shouldStop(BudgetContext ctx) {
        return check(ctx) == BudgetStatus.BLOCK;
    }

    /**
     * 是否应该降级（切换到更便宜模型）。
     */
    public boolean shouldDegrade(BudgetContext ctx) {
        BudgetStatus status = check(ctx);
        return status == BudgetStatus.DEGRADE || status == BudgetStatus.BLOCK;
    }

    /**
     * 多 Agent 协作预算预检（防 Token 乘数效应）。
     *
     * @param estimatedTokens 预估总 Token 消耗
     * @param remainingBudget 剩余预算
     * @return true 表示预检通过
     */
    public boolean checkCollaborationBudget(long estimatedTokens, long remainingBudget) {
        if (estimatedTokens > remainingBudget) {
            log.warn("协作预算预检未通过: estimated={}, remaining={}", estimatedTokens, remainingBudget);
            return false;
        }
        return true;
    }
}
