package com.gewu.agent.engine.orchestration;

import com.gewu.agent.engine.budget.BudgetContext;
import com.gewu.agent.engine.budget.BudgetController;
import com.gewu.agent.engine.budget.BudgetStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 自主循环防失控六重边界统一守卫。
 * <p>六重边界：
 * <ol>
 *   <li>迭代上限 - maxIterations</li>
 *   <li>Token 预算 - budgetController.shouldStop</li>
 *   <li>时间预算 - timeBudgetMs</li>
 *   <li>强制 HITL 节点 - 关键操作须人工审批</li>
 *   <li>重试次数 - maxRetriesPerNode</li>
 *   <li>资源配额 - maxToolCalls</li>
 * </ol>
 *
 * @since 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class AntiRunawayGuard {

    private final BudgetController budgetController;

    /**
     * 检查六重边界。
     *
     * @param iteration      当前迭代次数
     * @param maxIterations  最大迭代次数
     * @param budget         预算上下文
     * @param startTime      开始时间
     * @param timeBudgetMs   时间预算
     * @param retryCount     当前重试次数
     * @param maxRetries     最大重试次数
     * @param toolCallCount  工具调用次数
     * @param maxToolCalls   最大工具调用次数
     * @return 守卫结果（pass/blocked + 触发的边界名）
     */
    public GuardResult check(int iteration, int maxIterations,
                             BudgetContext budget, long startTime, long timeBudgetMs,
                             int retryCount, int maxRetries,
                             int toolCallCount, int maxToolCalls) {

        // 边界1: 迭代上限
        if (iteration >= maxIterations) {
            return blocked("ITERATION_LIMIT", "迭代次数超限: " + iteration + "/" + maxIterations);
        }

        // 边界2: Token 预算
        if (budget != null) {
            BudgetStatus budgetStatus = budgetController.check(budget);
            if (budgetStatus == BudgetStatus.BLOCK) {
                return blocked("TOKEN_BUDGET",
                        "Token 预算熔断: " + budget.getTokenConsumed() + "/" + budget.getTokenBudget());
            }
        }

        // 边界3: 时间预算
        if (timeBudgetMs > 0) {
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed > timeBudgetMs) {
                return blocked("TIME_BUDGET",
                        "时间预算超限: " + elapsed + "ms/" + timeBudgetMs + "ms");
            }
        }

        // 边界4: 强制 HITL 节点（由编排图 HUMAN 节点触发，此处不检查）
        // 边界5: 重试次数
        if (maxRetries > 0 && retryCount >= maxRetries) {
            return blocked("RETRY_LIMIT",
                    "重试次数超限: " + retryCount + "/" + maxRetries);
        }

        // 边界6: 资源配额
        if (maxToolCalls > 0 && toolCallCount >= maxToolCalls) {
            return blocked("RESOURCE_QUOTA",
                    "工具调用配额超限: " + toolCallCount + "/" + maxToolCalls);
        }

        return GuardResult.pass();
    }

    private GuardResult blocked(String boundary, String reason) {
        log.warn("AntiRunawayGuard 触发边界: {} - {}", boundary, reason);
        return GuardResult.blocked(boundary, reason);
    }

    /**
     * 守卫结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GuardResult {
        /** 是否通过 */
        private boolean passed;
        /** 是否被阻塞 */
        private boolean blocked;
        /** 触发的边界名 */
        private String boundary;
        /** 阻塞原因 */
        private String reason;

        public static GuardResult pass() {
            return GuardResult.builder().passed(true).blocked(false).build();
        }

        public static GuardResult blocked(String boundary, String reason) {
            return GuardResult.builder().passed(false).blocked(true).boundary(boundary).reason(reason).build();
        }
    }
}