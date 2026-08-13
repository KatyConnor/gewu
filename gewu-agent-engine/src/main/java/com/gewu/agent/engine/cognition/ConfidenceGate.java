package com.gewu.agent.engine.cognition;

import lombok.extern.slf4j.Slf4j;

/**
 * 置信度门控 - 按需触发的置信度评估，替代并行元认知监控。
 * <p>基于加权评分计算置信分，按阈值做出四级决策：
 * <ul>
 *   <li>score ≥ 0.85 -> {@link GateDecision.Action#ADOPT}（采纳）</li>
 *   <li>0.6 ≤ score < 0.85 -> {@link GateDecision.Action#RETRY_CHANGE_MODEL}（换模型重试）</li>
 *   <li>0.4 ≤ score < 0.6 -> {@link GateDecision.Action#RETRY_CHANGE_CONTEXT}（调整上下文重试）</li>
 *   <li>score < 0.4 -> {@link GateDecision.Action#ESCALATE_HITL}（升级人工）</li>
 * </ul>
 * <p>设计原则：按需触发（任务失败后/复杂任务完成后/置信分持续低位时），非并行监控，成本可控。
 *
 * @since 1.0.0
 */
@Slf4j
public class ConfidenceGate {

    /** 采纳阈值 */
    private static final double ADOPT_THRESHOLD = 0.85;
    /** 换模型重试阈值 */
    private static final double RETRY_MODEL_THRESHOLD = 0.6;
    /** 调整上下文重试阈值 */
    private static final double RETRY_CONTEXT_THRESHOLD = 0.4;

    // 权重
    private static final double W_CRITIC_SCORE = 0.4;
    private static final double W_TOOL_SUCCESS = 0.25;
    private static final double W_HISTORICAL = 0.2;
    private static final double W_CONTRACT = 0.15;

    /**
     * 评估置信度。
     *
     * @param criticScore      认知引擎 Critic 评分（0~1），无 Critic 时传 0.5
     * @param toolSuccessRate  工具调用成功率（0~1），无工具调用时传 1.0
     * @param historicalSimilarity 历史相似任务成功率（0~1），无历史数据时传 0.5
     * @param contractPassRate 制品契约通过率（0~1），无契约时传 1.0
     * @return 门控决策
     */
    public GateDecision evaluate(double criticScore, double toolSuccessRate,
                                 double historicalSimilarity, double contractPassRate) {
        double score = W_CRITIC_SCORE * clamp(criticScore)
                + W_TOOL_SUCCESS * clamp(toolSuccessRate)
                + W_HISTORICAL * clamp(historicalSimilarity)
                + W_CONTRACT * clamp(contractPassRate);

        GateDecision decision;
        if (score >= ADOPT_THRESHOLD) {
            decision = GateDecision.adopt(score, "置信度达标，采纳结果");
        } else if (score >= RETRY_MODEL_THRESHOLD) {
            decision = GateDecision.retryModel(score, "置信度中等，建议换模型重试");
        } else if (score >= RETRY_CONTEXT_THRESHOLD) {
            decision = GateDecision.retryContext(score, "置信度偏低，建议调整上下文重试");
        } else {
            decision = GateDecision.escalateHitl(score, "置信度过低，升级人工介入");
        }

        log.debug("ConfidenceGate.evaluate: critic={}, toolSuccess={}, historical={}, contract={} -> score={}, action={}",
                criticScore, toolSuccessRate, historicalSimilarity, contractPassRate, score, decision.getAction());
        return decision;
    }

    /**
     * 简化评估 - 仅基于 Critic 评分。
     */
    public GateDecision evaluate(double criticScore) {
        return evaluate(criticScore, 1.0, 0.5, 1.0);
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
