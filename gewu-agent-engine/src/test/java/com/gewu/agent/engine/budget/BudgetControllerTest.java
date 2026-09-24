package com.gewu.agent.engine.budget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
    @DisplayName("时间超限不再 BLOCK：仅告警信号，滚动续期后恢复 NORMAL（S9 方案A）")
    void timeExhaustedDoesNotBlock() {
        BudgetContext ctx = controller.createBudget(null);
        ctx.setTokenConsumed(0);
        ctx.setTimeBudgetMs(300_000);
        // 模拟时间耗尽：startTimeMs 回拨，使 elapsed=300s
        ctx.setStartTimeMs(System.currentTimeMillis() - 300_000);
        // 时间维度单独超限：不 BLOCK
        assertThat(controller.check(ctx)).isNotEqualTo(BudgetStatus.BLOCK);
        // 滚动续期：新时间预算 = elapsed + max(base/2, 30s)，恢复 NORMAL
        assertThat(controller.renewTimeBudget(ctx)).isTrue();
        assertThat(ctx.getTimeBudgetMs()).isGreaterThan(300_000);
        assertThat(controller.check(ctx)).isEqualTo(BudgetStatus.NORMAL);
        // 时间未满时续期是空操作
        assertThat(controller.renewTimeBudget(ctx)).isFalse();
    }

    @Test
    @DisplayName("Token 超限仍然 BLOCK（时间续期不影响 Token 阻断）")
    void tokenExhaustedStillBlocksAfterRenewal() {
        BudgetContext ctx = controller.createBudget(null);
        ctx.setStartTimeMs(System.currentTimeMillis() - 300_000);
        controller.renewTimeBudget(ctx);
        ctx.setTokenConsumed(100_000);
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

    @Test
    @DisplayName("成本利用率：costBudget≤0 时恒 0（金额熔断未启用）")
    void costUtilizationDisabledWithoutBudget() {
        BudgetContext ctx = controller.createBudget(null);
        // 未配置金额上限：costBudget 为旧派生伪金额，但 utilization 语义以配置为准——
        // 这里直接验证 getCostUtilization 的守卫逻辑
        ctx.setCostBudget(0);
        ctx.setCostConsumed(5.0);
        assertThat(ctx.getCostUtilization()).isEqualTo(0);
    }

    @Test
    @DisplayName("金额熔断（优化2）：配置 cost-budget 后成本超限触发 BLOCK，未配置时不触发")
    void costBlockingOnlyWhenConfigured() {
        // 配置金额上限 1 元
        BudgetController withCost = new BudgetController(100_000, 300_000, 10,
                new BudgetController.Quotas(), 1.0, null);
        BudgetContext ctx = withCost.createBudget(null);
        assertThat(ctx.getCostBudget()).isEqualTo(1.0);

        // 成本未超限：即使 token 维度也不超限，保持 NORMAL
        ctx.setCostConsumed(0.5);
        assertThat(withCost.check(ctx)).isNotEqualTo(BudgetStatus.BLOCK);

        // 成本超限：token 消耗为零也 BLOCK（成本维独立触发）
        ctx.setCostConsumed(1.0);
        assertThat(withCost.check(ctx)).isEqualTo(BudgetStatus.BLOCK);
        assertThat(withCost.shouldStop(ctx)).isTrue();

        // 未配置金额上限（默认 0）：同样的成本消耗不触发 BLOCK
        BudgetContext plain = controller.createBudget(null);
        plain.setCostConsumed(999.0);
        assertThat(controller.check(plain)).isNotEqualTo(BudgetStatus.BLOCK);
    }

    @Test
    @DisplayName("金额上限等级缩放：L1=÷l1TokenDivisor、L3=×l3TokenMultiplier")
    void costBudgetScalesWithLevel() {
        BudgetController withCost = new BudgetController(100_000, 300_000, 10,
                new BudgetController.Quotas(), 1.0, null);
        assertThat(withCost.createBudget("L1").getCostBudget()).isCloseTo(0.2, within(1e-9));
        assertThat(withCost.createBudget(null).getCostBudget()).isCloseTo(1.0, within(1e-9));
        assertThat(withCost.createBudget("L3").getCostBudget()).isCloseTo(3.0, within(1e-9));

        // 未配置金额上限时维持旧派生伪金额（仅观测）
        assertThat(controller.createBudget(null).getCostBudget()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("calculateCost：未装配计算器时回退统一假单价")
    void calculateCostFallsBackToUniformPrice() {
        assertThat(controller.calculateCost("any-model", 1000, 2000))
                .isEqualTo(3000 * 0.00001);
    }

    @Test
    @DisplayName("配额熔断开关（配额体系）：blockEnabled=false 时 token 超限仅告警不熔断")
    void tokenBlockGatedByBlockEnabled() {
        BudgetContext ctx = controller.createBudget(null);
        // 开关关闭：token 100% 不 BLOCK（用户偏好=仅提醒）
        ctx.setBlockEnabled(false);
        ctx.setTokenConsumed(100_000);
        assertThat(controller.check(ctx)).isNotEqualTo(BudgetStatus.BLOCK);
        // 轮次超限不受开关控制（失控防线）
        ctx.setCurrentRound(10);
        assertThat(controller.check(ctx)).isEqualTo(BudgetStatus.BLOCK);

        // 开关开启（缺省）：token 100% 照常 BLOCK
        BudgetContext strict = controller.createBudget(null);
        assertThat(strict.getBlockEnabled()).isTrue();
        strict.setTokenConsumed(100_000);
        assertThat(controller.check(strict)).isEqualTo(BudgetStatus.BLOCK);
    }

    @Test
    @DisplayName("轮次滚动扩容：renewRounds 扩 50%（≥10 轮）并累计次数")
    void renewRoundsExpandsAndCounts() {
        BudgetContext ctx = controller.createBudget(null);
        ctx.setMaxRounds(50);
        assertThat(controller.renewRounds(ctx)).isTrue();
        // 50 → 75（max(25, 10)=25），扩容次数 +1
        assertThat(ctx.getMaxRounds()).isEqualTo(75);
        assertThat(ctx.getRoundsRenewed()).isEqualTo(1);

        // 小基线：2 轮 → 扩容下限 10 轮生效（2+10=12）
        BudgetContext small = controller.createBudget(null);
        small.setMaxRounds(2);
        assertThat(controller.renewRounds(small)).isTrue();
        assertThat(small.getMaxRounds()).isEqualTo(12);
        assertThat(small.getRoundsRenewed()).isEqualTo(1);
    }

    @Test
    @DisplayName("续期重置告警去重水位：时间/轮次续期后允许新周期再告警")
    void renewalsResetAlertLevel() {
        BudgetContext ctx = controller.createBudget(null);
        ctx.setLastAlertLevel(2);
        ctx.setStartTimeMs(System.currentTimeMillis() - 300_000);
        controller.renewTimeBudget(ctx);
        assertThat(ctx.getLastAlertLevel()).isZero();
        assertThat(ctx.getTimeRenewals()).isEqualTo(1);

        ctx.setLastAlertLevel(1);
        controller.renewRounds(ctx);
        assertThat(ctx.getLastAlertLevel()).isZero();
        assertThat(ctx.getRoundsRenewed()).isEqualTo(1);
    }

    @Test
    @DisplayName("阈值可配：自定义 alert/degrade 阈值生效于 check 状态机")
    void configurableThresholds() {
        BudgetController custom = new BudgetController(100_000, 300_000, 10,
                new BudgetController.Quotas(), 0, null, 0.5, 0.8);
        BudgetContext ctx = custom.createBudget(null);
        ctx.setTokenConsumed(55_000);
        assertThat(custom.check(ctx)).isEqualTo(BudgetStatus.ALERT);
        ctx.setTokenConsumed(85_000);
        assertThat(custom.check(ctx)).isEqualTo(BudgetStatus.DEGRADE);
        // 默认构造保持 0.70/0.90 历史行为
        BudgetContext plain = controller.createBudget(null);
        plain.setTokenConsumed(55_000);
        assertThat(controller.check(plain)).isEqualTo(BudgetStatus.NORMAL);
    }
}
