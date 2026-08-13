package com.gewu.agent.engine.cognition;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 置信度门控决策 - 置信度评估后的动作建议。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GateDecision {

    /** 决策动作 */
    private Action action;

    /** 置信分（0~1） */
    private double score;

    /** 决策原因 */
    private String reason;

    /** 建议的策略（如换模型时指定模型） */
    private String strategy;

    /**
     * 决策动作枚举。
     */
    public enum Action {
        /** 采纳结果（score ≥ 0.85） */
        ADOPT,
        /** 换模型重试（0.6 ≤ score < 0.85） */
        RETRY_CHANGE_MODEL,
        /** 调整上下文重试（0.4 ≤ score < 0.6） */
        RETRY_CHANGE_CONTEXT,
        /** 升级人工介入（score < 0.4） */
        ESCALATE_HITL
    }

    public static GateDecision adopt(double score, String reason) {
        return GateDecision.builder().action(Action.ADOPT).score(score).reason(reason).build();
    }

    public static GateDecision retryModel(double score, String reason) {
        return GateDecision.builder().action(Action.RETRY_CHANGE_MODEL).score(score).reason(reason).build();
    }

    public static GateDecision retryContext(double score, String reason) {
        return GateDecision.builder().action(Action.RETRY_CHANGE_CONTEXT).score(score).reason(reason).build();
    }

    public static GateDecision escalateHitl(double score, String reason) {
        return GateDecision.builder().action(Action.ESCALATE_HITL).score(score).reason(reason).build();
    }
}
