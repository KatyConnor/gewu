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

    public BudgetController(long defaultTokenBudget, long defaultTimeBudgetMs, int defaultMaxRounds) {
        this.defaultTokenBudget = defaultTokenBudget;
        this.defaultTimeBudgetMs = defaultTimeBudgetMs;
        this.defaultMaxRounds = defaultMaxRounds;
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

        if ("L1".equals(taskLevel)) {
            tokenBudget = defaultTokenBudget / 5;
            timeBudgetMs = 30_000;
            maxRounds = 3;
        } else if ("L3".equals(taskLevel)) {
            tokenBudget = defaultTokenBudget * 3;
            timeBudgetMs = defaultTimeBudgetMs * 4;
            maxRounds = defaultMaxRounds * 2;
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
     * 检查预算状态。
     */
    public BudgetStatus check(BudgetContext ctx) {
        if (ctx == null) return BudgetStatus.NORMAL;

        double tokenUtil = ctx.getTokenUtilization();
        double timeUtil = ctx.getTimeUtilization();
        double maxUtil = Math.max(tokenUtil, timeUtil);

        if (maxUtil >= BLOCK_THRESHOLD || ctx.getCurrentRound() >= ctx.getMaxRounds()) {
            return BudgetStatus.BLOCK;
        } else if (maxUtil >= DEGRADE_THRESHOLD) {
            return BudgetStatus.DEGRADE;
        } else if (maxUtil >= ALERT_THRESHOLD) {
            return BudgetStatus.ALERT;
        }
        return BudgetStatus.NORMAL;
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
