package com.gewu.agent.engine.budget;

/**
 * 预算状态 - 四维预算检查的结果。
 *
 * @since 1.0.0
 */
public enum BudgetStatus {

    /** 正常（利用率 < 70%） */
    NORMAL,

    /** 告警（利用率 70%-90%），建议降级到更便宜模型 */
    ALERT,

    /** 降级（利用率 90%-100%），强制使用最便宜模型 */
    DEGRADE,

    /** 熔断（利用率 ≥ 100%），终止执行 */
    BLOCK
}
