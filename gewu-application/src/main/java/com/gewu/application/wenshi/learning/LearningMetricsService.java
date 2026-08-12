package com.gewu.application.wenshi.learning;

import com.gewu.domain.wenshi.learning.Experience;
import com.gewu.infrastructure.mapper.wenshi.ExperienceMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 学习指标服务 — 统计和汇总学习模块的运营指标。
 * <p>
 * 提供多维度指标：
 * <ul>
 *   <li>经验总量与成功计数</li>
 *   <li>成功率与平均得分</li>
 *   <li>经验复用次数统计</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningMetricsService {

    private final ExperienceMapper experienceMapper;

    /**
     * 获取指定租户的学习指标。
     *
     * @param tenantId 租户 ID
     * @return 学习指标数据，无经验时返回空指标
     * @since 1.0.0
     */
    public LearningMetrics getMetrics(String tenantId) {
        List<Experience> experiences = experienceMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Experience>()
                        .eq(Experience::getTenantId, tenantId)
        );

        if (experiences.isEmpty()) {
            return LearningMetrics.empty();
        }

        long successCount = experiences.stream()
                .filter(e -> "SUCCESS".equals(e.getOutcome()))
                .count();

        double avgScore = experiences.stream()
                .filter(e -> e.getScore() != null)
                .mapToDouble(e -> e.getScore().doubleValue())
                .average()
                .orElse(0);

        long totalHits = experiences.stream()
                .filter(e -> e.getHitCount() != null)
                .mapToLong(Experience::getHitCount)
                .sum();

        return LearningMetrics.builder()
                .totalExperiences(experiences.size())
                .successCount((int) successCount)
                .successRate((double) successCount / experiences.size())
                .averageScore(avgScore)
                .totalReuseHits(totalHits)
                .averageReusePerExperience(experiences.isEmpty() ? 0 : (double) totalHits / experiences.size())
                .build();
    }

    /**
     * 学习指标 — 封装学习模块的运营数据。
     *
     * @since 1.0.0
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LearningMetrics {
        /** 经验总数 */
        private int totalExperiences;
        /** 成功经验计数 */
        private int successCount;
        /** 成功率（0~1） */
        private double successRate;
        /** 平均得分 */
        private double averageScore;
        /** 经验复用总命中次数 */
        private long totalReuseHits;
        /** 每条经验的平均复用次数 */
        private double averageReusePerExperience;

        /**
         * 构建空指标对象（所有值为零）。
         *
         * @return 空指标实例
         * @since 1.0.0
         */
        public static LearningMetrics empty() {
            return LearningMetrics.builder()
                    .totalExperiences(0)
                    .successCount(0)
                    .successRate(0)
                    .averageScore(0)
                    .totalReuseHits(0)
                    .averageReusePerExperience(0)
                    .build();
        }
    }
}
