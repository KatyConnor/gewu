package com.gewu.agent.engine.orchestration;

import com.gewu.agent.engine.budget.BudgetContext;
import com.gewu.agent.engine.budget.BudgetController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AntiRunawayGuard} 六重边界测试。
 */
@DisplayName("防失控守卫")
class AntiRunawayGuardTest {

    private final BudgetController budgetController = new BudgetController(1000, 300000, 10);
    private final AntiRunawayGuard guard = new AntiRunawayGuard(budgetController);

    private BudgetContext freshBudget() {
        return budgetController.createBudget(null);
    }

    @Test
    @DisplayName("全部边界内通过")
    void allWithinBoundsPasses() {
        var result = guard.check(1, 5, freshBudget(),
                System.currentTimeMillis(), 300000, 0, 3, 5, 50);
        assertThat(result.isPassed()).isTrue();
        assertThat(result.isBlocked()).isFalse();
    }

    @Test
    @DisplayName("边界1：迭代次数超限")
    void iterationLimitBlocked() {
        var result = guard.check(5, 5, freshBudget(),
                System.currentTimeMillis(), 300000, 0, 3, 5, 50);
        assertThat(result.isBlocked()).isTrue();
        assertThat(result.getBoundary()).isEqualTo("ITERATION_LIMIT");
    }

    @Test
    @DisplayName("边界2：Token 预算熔断")
    void tokenBudgetBlocked() {
        BudgetContext budget = freshBudget();
        budgetController.consume(budget, 2000, 0); // 200% > 100%
        var result = guard.check(1, 5, budget,
                System.currentTimeMillis(), 300000, 0, 3, 5, 50);
        assertThat(result.isBlocked()).isTrue();
        assertThat(result.getBoundary()).isEqualTo("TOKEN_BUDGET");
    }

    @Test
    @DisplayName("边界3：时间预算超限")
    void timeBudgetBlocked() {
        var result = guard.check(1, 5, freshBudget(),
                System.currentTimeMillis() - 10000, 5000, 0, 3, 5, 50);
        assertThat(result.isBlocked()).isTrue();
        assertThat(result.getBoundary()).isEqualTo("TIME_BUDGET");
    }

    @Test
    @DisplayName("边界5：重试次数超限")
    void retryLimitBlocked() {
        var result = guard.check(1, 5, freshBudget(),
                System.currentTimeMillis(), 300000, 3, 3, 5, 50);
        assertThat(result.isBlocked()).isTrue();
        assertThat(result.getBoundary()).isEqualTo("RETRY_LIMIT");
    }

    @Test
    @DisplayName("边界6：工具调用配额超限")
    void toolQuotaBlocked() {
        var result = guard.check(1, 5, freshBudget(),
                System.currentTimeMillis(), 300000, 0, 3, 50, 50);
        assertThat(result.isBlocked()).isTrue();
        assertThat(result.getBoundary()).isEqualTo("RESOURCE_QUOTA");
    }

    @Test
    @DisplayName("预算为 null 与零阈值时跳过对应边界检查")
    void nullBudgetAndZeroThresholdsSkipped() {
        // budget=null、timeBudgetMs=0、maxRetries=0、maxToolCalls=0 均不触发
        var result = guard.check(1, 5, null, System.currentTimeMillis(), 0, 99, 0, 99, 0);
        assertThat(result.isPassed()).isTrue();
    }
}
