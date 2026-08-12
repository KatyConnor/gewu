package com.gewu.application.wenshi.learning;

import com.gewu.domain.wenshi.learning.Experience;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 质量评估器 — 对经验进行准入质量把关。
 * <p>
 * 采用多层过滤策略，确保只有高质量经验进入知识库：
 * <ul>
 *   <li>场景描述非空校验</li>
 *   <li>策略描述非空校验</li>
 *   <li>得分阈值过滤（最低 0.6）</li>
 *   <li>场景描述模糊度检查（长度 &lt; 10 视为模糊）</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Slf4j
@Service
public class QualityAssessor {

    /** 经验最低通过得分 */
    private static final BigDecimal MIN_SCORE = BigDecimal.valueOf(0.6);
    /** 经验相似度上限，超过视为重复 */
    private static final double MAX_SIMILARITY = 0.9;

    /**
     * 对经验进行质量评估。
     * <p>
     * 依次执行非空校验、得分校验、模糊度校验，任一层不通过即拒绝。
     *
     * @param experience 待评估的经验对象
     * @return 评估结果，包含是否通过、得分及原因
     * @since 1.0.0
     */
    public QualityResult assess(Experience experience) {
        if (experience.getScenario() == null || experience.getScenario().isBlank()) {
            return reject("场景描述为空");
        }

        if (experience.getStrategy() == null || experience.getStrategy().isBlank()) {
            return reject("策略描述为空");
        }

        if (experience.getScore() == null || experience.getScore().compareTo(MIN_SCORE) < 0) {
            return reject("得分过低: " + experience.getScore());
        }

        // 场景描述过短无法支撑后续匹配，标记为需 LLM 精评
        if (experience.getScenario().length() < 10) {
            return reject("场景描述过于模糊，需要 LLM 精评");
        }

        return QualityResult.builder()
                .accepted(true)
                .score(experience.getScore())
                .reason("质量评估通过")
                .build();
    }

    /**
     * 构建拒绝结果。
     *
     * @param reason 拒绝原因
     * @return 未通过的质量结果
     * @since 1.0.0
     */
    private QualityResult reject(String reason) {
        return QualityResult.builder()
                .accepted(false)
                .score(BigDecimal.ZERO)
                .reason(reason)
                .build();
    }

    /**
     * 质量评估结果 — 封装评估结论。
     *
     * @since 1.0.0
     */
    @Data
    @Builder
    public static class QualityResult {
        /** 是否通过质量评估 */
        private boolean accepted;
        /** 经验得分 */
        private BigDecimal score;
        /** 评估原因（通过/拒绝说明） */
        private String reason;
    }
}
