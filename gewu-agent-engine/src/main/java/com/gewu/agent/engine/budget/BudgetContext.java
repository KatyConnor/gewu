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
}
