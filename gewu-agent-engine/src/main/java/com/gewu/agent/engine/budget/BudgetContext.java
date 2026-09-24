package com.gewu.agent.engine.budget;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 预算上下文 - 贯穿全管线的四维预算追踪（Token/时间/成本/轮次）。
 * <p>每次执行创建一个实例，随执行流程传递，实时记录消耗量。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BudgetContext {

    /** 任务等级（决定预算配额） */
    private String taskLevel;

    /** Token 预算上限 */
    private long tokenBudget;

    /** 已消耗 Token */
    private long tokenConsumed;

    /** 时间预算（毫秒） */
    private long timeBudgetMs;

    /** 执行开始时间（毫秒） */
    private long startTimeMs;

    /** 成本预算（元） */
    private double costBudget;

    /** 已消耗成本（元） */
    private double costConsumed;

    /** 最大轮次 */
    private int maxRounds;

    /** 当前轮次 */
    private int currentRound;

    /** 配额熔断开关（默认 true；false=token 耗尽仅告警不熔断，轮次/成本维不受其控制） */
    @Builder.Default
    private Boolean blockEnabled = true;

    /** 本执行已发生的上下文压缩次数（上下文自治：接近模型窗口时压缩历史续跑） */
    @Builder.Default
    private int contextCompactions = 0;

    /** 告警去重：最近一次已发送告警的级别（0=未告警,1=ALERT,2=DEGRADE）；仅在级别升级时重发 */
    @Builder.Default
    private int lastAlertLevel = 0;

    /** 本执行已发生的时间预算滚动续期次数 */
    @Builder.Default
    private int timeRenewals = 0;

    /** 本执行已发生的轮次预算滚动扩容次数 */
    @Builder.Default
    private int roundsRenewed = 0;

    /** Token 预算是否为不限量哨兵（未绑定套餐用户）：告警文案据此省略 token 维，避免恒显 0% 造成误导 */
    @Builder.Default
    private boolean tokenUnlimited = false;

    /**
     * 计算已耗时（毫秒）。
     */
    public long getElapsedMs() {
        return System.currentTimeMillis() - startTimeMs;
    }

    /**
     * 计算 Token 预算利用率。
     */
    public double getTokenUtilization() {
        return tokenBudget > 0 ? (double) tokenConsumed / tokenBudget : 0;
    }

    /**
     * 计算时间预算利用率。
     */
    public double getTimeUtilization() {
        return timeBudgetMs > 0 ? (double) getElapsedMs() / timeBudgetMs : 0;
    }

    /**
     * 计算成本（金额）预算利用率。
     * <p>costBudget 未设置（≤0）时恒为 0——金额熔断以"配置了正数上限"为启用条件。
     */
    public double getCostUtilization() {
        return costBudget > 0 ? costConsumed / costBudget : 0;
    }
}
