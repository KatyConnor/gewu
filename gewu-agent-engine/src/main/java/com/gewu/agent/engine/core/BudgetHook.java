package com.gewu.agent.engine.core;

import com.gewu.agent.engine.budget.BudgetContext;
import com.gewu.agent.engine.AgentEngineException;
import com.gewu.agent.engine.budget.BudgetController;
import com.gewu.agent.engine.llm.model.LlmResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 预算钩子（AE-P2-1 三期步5）：主循环预算控制面操作的集中组件——
 * 循环头（先续期、后熔断）/ 轮次记账 / 截断自愈预算扩容。
 * <p>从 ReactAgentExecutor 主循环外移（三期步5，行为逐字节一致）；
 * 流式路径预算序列与事件发射交织，维持内联（提取待流式预算重构窗口）。
 * D-6 接线时的预算分级扩展（modelTier 驱动配额）在本组件演进。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-15
 */
@Slf4j
class BudgetHook {

    private final ReactAgentExecutor owner;
    private final BudgetController budgetController;

    BudgetHook(ReactAgentExecutor owner, BudgetController budgetController) {
        this.owner = owner;
        this.budgetController = budgetController;
    }

    /**
     * 循环头（S9 方案A，同步/流式共用顺序）：时间预算滚动续期 → 熔断检查。
     * 熔断（仅 Token/轮次阻断）抛 BUDGET_EXCEEDED 终止循环。
     */
    void roundHead(BudgetContext budget, String agentId, int round) {
        // 时间预算滚动续期（S9 方案A）：时间满额但 Token/轮次健康 → 续期不终止
        budgetController.renewTimeBudget(budget);
        // 预算检查：熔断则终止（仅 Token/轮次，配置金额上限后含成本维）
        if (budgetController.shouldStop(budget)) {
            log.warn("预算熔断: tokenUtil={}, timeUtil={}, costUtil={}, round={}",
                    budget.getTokenUtilization(), budget.getTimeUtilization(),
                    budget.getCostUtilization(), round);
            owner.recordMetricSafe("agent.budget.exceeded", 1, java.util.Map.of(
                    "agentId", agentId != null ? agentId : "unknown", "reason", "sync_loop"));
            throw AgentEngineException.of("BUDGET_EXCEEDED",
                    owner.budgetExceededMessage(budget)
                            + ", time=" + budget.getElapsedMs() + "ms/" + budget.getTimeBudgetMs() + "ms");
        }
    }

    /**
     * 轮次记账（优化2：成本按模型定价对输入/输出拆分计价；
     * usage 缺失拆分时全部计入输入侧——保守处理）。
     *
     * @return [tokens, completionTokens]（后续事件与账本使用）
     */
    long[] consumeRound(BudgetContext budget, String model, LlmResponse.Usage usage) {
        long promptTokens = usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
        long tokens;
        long completionTokens;
        if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
            tokens = usage.getTotalTokens();
            completionTokens = usage.getCompletionTokens() != null
                    ? usage.getCompletionTokens() : Math.max(0, tokens - promptTokens);
        } else {
            tokens = promptTokens;
            completionTokens = 0;
        }
        budgetController.consume(budget, tokens,
                budgetController.calculateCost(model, promptTokens, completionTokens));
        return new long[]{tokens, completionTokens};
    }

    /**
     * 截断自愈预算扩容（S8/S9）：主动升级 token/时间/轮次预算，
     * 使重试轮次不被循环头熔断（尤其 L1 级仅 30s 时间预算）。
     */
    void expandForTruncationRetry(BudgetContext budget) {
        budget.setTokenBudget(budget.getTokenBudget() * 2);
        budget.setTimeBudgetMs(Math.max(budget.getTimeBudgetMs() * 2,
                budget.getElapsedMs() * 3));
        budget.setMaxRounds(budget.getMaxRounds() + 1);
    }
}
