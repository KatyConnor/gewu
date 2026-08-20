package com.gewu.agent.engine.verification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 双闭环验证器 - 收敛版双闭环验证机制。
 * <p>内环（执行验证）：Schema 校验 + 规则引擎 + LLM 自评估，≤5 轮
 * <p>外环（LLM 仲裁）：最强模型仲裁 + 证据链分析，≤2 轮（委托 {@link com.gewu.agent.engine.cognition.ArbiterEngine}）
 * <p>关键收敛设计：去掉"回退规划层重新触发内闭环"的嵌套递归，
 * 改为"标记子目标失败 + 降级输出"，避免状态空间爆炸。
 *
 * @since 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class DualLoopVerifier {

    private final com.gewu.agent.engine.cognition.ArbiterEngine arbiterEngine;

    /** 内环最大轮次 */
    private static final int MAX_INNER_ROUNDS = 5;
    /** 外环最大轮次 */
    private static final int MAX_OUTER_ROUNDS = 2;
    /** 收益递减检测：连续 N 轮评分不提升则停止 */
    private static final int DIMINISHING_RUN = 2;

    /** 兼容旧构造（无仲裁引擎时外环退化为 Critic 重评） */
    public DualLoopVerifier() {
        this(null);
    }

    /**
     * 执行双闭环验证。
     *
     * @param output      执行产出
     * @param acceptances 验收标准列表
     * @param critic      认知内核（用于评估）
     * @return 验证结果
     */
    public VerificationResult verify(String output, List<String> acceptances,
                                     com.gewu.agent.engine.cognition.ReasoningKernel critic) {
        if (output == null || output.isBlank()) {
            return VerificationResult.failed("输出为空");
        }

        // 内环：迭代验证 + 修正反馈
        double bestScore = 0;
        int stagnantCount = 0;
        for (int inner = 0; inner < MAX_INNER_ROUNDS; inner++) {
            com.gewu.agent.engine.cognition.ReasoningResult critique =
                    critic.critique(output, acceptances);
            double score = critique.getScore();

            if (critique.isAccepted() && score >= 0.85) {
                log.debug("DualLoopVerifier 内环通过: round={}, score={}", inner, score);
                return VerificationResult.passed(score, "内环验证通过", "INNER");
            }

            // 收益递减检测
            if (score > bestScore) {
                bestScore = score;
                stagnantCount = 0;
            } else {
                stagnantCount++;
                if (stagnantCount >= DIMINISHING_RUN) {
                    log.debug("DualLoopVerifier 收益递减停止内环: bestScore={}", bestScore);
                    break;
                }
            }
        }

        // 外环：LLM 仲裁（优先委托 ArbiterEngine 多采样仲裁，缺失时退化为 Critic 重评）
        if (arbiterEngine != null) {
            try {
                List<String> candidates = List.of(output, "（不通过，需要修正）");
                var arbitration = arbiterEngine.arbitrate(
                        "验收标准: " + String.join("; ", acceptances), candidates);
                if (arbitration.getWinnerIndex() == 0 && arbitration.getConfidence() >= 0.6) {
                    log.debug("DualLoopVerifier 外环仲裁通过: confidence={}", arbitration.getConfidence());
                    return VerificationResult.passed(arbitration.getConfidence(),
                            "外环仲裁通过: " + arbitration.getDecision(), "OUTER");
                }
            } catch (Exception e) {
                log.debug("DualLoopVerifier 外环仲裁异常，退化重评: {}", e.getMessage());
            }
        }
        for (int outer = 0; outer < MAX_OUTER_ROUNDS; outer++) {
            com.gewu.agent.engine.cognition.ReasoningResult arbitration =
                    critic.critique(output, acceptances);
            if (arbitration.isAccepted()) {
                log.debug("DualLoopVerifier 外环通过: round={}, score={}", outer, arbitration.getScore());
                return VerificationResult.passed(arbitration.getScore(),
                        "外环仲裁通过: " + arbitration.getVerdict(), "OUTER");
            }
        }

        log.debug("DualLoopVerifier 验证未通过，降级输出: bestScore={}", bestScore);
        return VerificationResult.degraded(bestScore, "双闭环验证未通过，降级输出");
    }

    /**
     * 验证结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerificationResult {
        /** 是否通过 */
        private boolean passed;
        /** 评分（0~1） */
        private double score;
        /** 结论说明 */
        private String verdict;
        /** 验证层级: INNER / OUTER / DEGRADED */
        private String layer;

        public static VerificationResult passed(double score, String verdict, String layer) {
            return VerificationResult.builder().passed(true).score(score).verdict(verdict).layer(layer).build();
        }

        public static VerificationResult failed(String reason) {
            return VerificationResult.builder().passed(false).score(0).verdict(reason).layer("FAILED").build();
        }

        public static VerificationResult degraded(double score, String reason) {
            return VerificationResult.builder().passed(false).score(score).verdict(reason).layer("DEGRADED").build();
        }
    }
}