package com.gewu.agent.engine.budget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BudgetController} 单元测试（T1.5 配额参数化验证）。
 */
@DisplayName("预算控制器")
class BudgetControllerTest {

    private final BudgetController controller = new BudgetController(100_000, 300_000, 10);

    @Test
    @DisplayName("L1 轻量任务：默认配额 = token/5、30s、3 轮")
    void l1DefaultQuotas() {
        BudgetContext ctx = controller.createBudget("L1");
        assertThat(ctx.getTokenBudget()).isEqualTo(20_000);
        assertThat(ctx.getTimeBudgetMs()).isEqualTo(30_000);
        assertThat(ctx.getMaxRounds()).isEqualTo(3);
        assertThat(ctx.getTaskLevel()).isEqualTo("L1");
    }

    @Test
    @DisplayName("L2 默认等级：全额配额")
    void l2DefaultQuotas() {
        BudgetContext ctx = controller.createBudget(null);
        assertThat(ctx.getTokenBudget()).isEqualTo(100_000);
        assertThat(ctx.getTimeBudgetMs()).isEqualTo(300_000);
        assertThat(ctx.getMaxRounds()).isEqualTo(10);
        assertThat(ctx.getTaskLevel()).isEqualTo("L2");
    }

    @Test
    @DisplayName("L3 复杂任务：默认配额 = token×3、时间×4、轮次×2")
    void l3DefaultQuotas() {
        BudgetContext ctx = controller.createBudget("L3");
        assertThat(ctx.getTokenBudget()).isEqualTo(300_000);
        assertThat(ctx.getTimeBudgetMs()).isEqualTo(1_200_000);
        assertThat(ctx.getMaxRounds()).isEqualTo(20);
    }

    @Test
    @DisplayName("自定义 Quotas：L1/L3 配额按配置计算")
    void customQuotas() {
        BudgetController.Quotas quotas = new BudgetController.Quotas();
        quotas.setL1TokenDivisor(2);
        quotas.setL1TimeBudgetMs(10_000);
        quotas.setL1MaxRounds(1);
        quotas.setL3TokenMultiplier(5);
        quotas.setL3TimeMultiplier(2);
        quotas.setL3RoundsMultiplier(3);
        BudgetController custom = new BudgetController(100_000, 300_000, 10, quotas);

        BudgetContext l1 = custom.createBudget("L1");
        assertThat(l1.getTokenBudget()).isEqualTo(50_000);
        assertThat(l1.getTimeBudgetMs()).isEqualTo(10_000);
        assertThat(l1.getMaxRounds()).isEqualTo(1);

        BudgetContext l3 = custom.createBudget("L3");
        assertThat(l3.getTokenBudget()).isEqualTo(500_000);
        assertThat(l3.getTimeBudgetMs()).isEqualTo(600_000);
        assertThat(l3.getMaxRounds()).isEqualTo(30);
    }

    @Test
    @DisplayName("阈值状态机：70% ALERT / 90% DEGRADE / 100% BLOCK")
    void thresholdStates() {
        BudgetContext ctx = controller.createBudget(null);
        ctx.setTokenConsumed(50_000);
        assertThat(controller.check(ctx)).isEqualTo(BudgetStatus.NORMAL);

        ctx.setTokenConsumed(75_000);
        assertThat(controller.check(ctx)).isEqualTo(BudgetStatus.ALERT);

        ctx.setTokenConsumed(95_000);
        assertThat(controller.check(ctx)).isEqualTo(BudgetStatus.DEGRADE);

        ctx.setTokenConsumed(100_000);
        assertThat(controller.check(ctx)).isEqualTo(BudgetStatus.BLOCK);
        assertThat(controller.shouldStop(ctx)).isTrue();
    }

    @Test
    @DisplayName("轮次耗尽同样触发 BLOCK")
    void roundsExhaustedBlocks() {
        BudgetContext ctx = controller.createBudget(null);
        ctx.setCurrentRound(10);
        assertThat(controller.check(ctx)).isEqualTo(BudgetStatus.BLOCK);
    }

    @Test
    @DisplayName("consume 记账：累加 token/成本并推进轮次")
    void consumeAccounting() {
        BudgetContext ctx = controller.createBudget(null);
        controller.consume(ctx, 1000, 0.01);
        controller.consume(ctx, 2000, 0.02);
        assertThat(ctx.getTokenConsumed()).isEqualTo(3000);
        assertThat(ctx.getCostConsumed()).isEqualTo(0.03);
        assertThat(ctx.getCurrentRound()).isEqualTo(2);
    }
}
