package com.gewu.application.wenshi.learning;

import com.gewu.infrastructure.mapper.wenshi.ExperienceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.domain.wenshi.learning.Experience;

import java.math.BigDecimal;
import java.util.List;

/**
 * 认知调度器 - Agent 自我进化的核心组件。
 * <p>定时监控质量指标，识别改进机会，按优先级排序触发自学习（受控模式）。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CognitiveScheduler {

    private final ExperienceMapper experienceMapper;
    private final LearningMetricsService learningMetricsService;
    private final ExperienceDistillationPipeline distillationPipeline;

    /** 质量阈值 */
    private static final double MIN_SUCCESS_RATE = 0.80;
    private static final double MIN_AVG_SCORE = 0.70;

    /**
     * 认知调度循环（每小时执行）。
     * <p>1. 质量监控 → 2. 识别改进机会 → 3. 优先级排序 → 4. 触发自学习
     */
    @Scheduled(cron = "0 0 * * * *")
    public void runCycle() {
        log.debug("认知调度器-循环开始");
        try {
            // 1. 质量监控
            LearningMetricsService.LearningMetrics metrics = learningMetricsService.getMetrics("default");

            if (metrics.getTotalExperiences() < 10) {
                log.debug("认知调度器-经验数据不足 ({})，跳过", metrics.getTotalExperiences());
                return;
            }

            // 2. 识别改进机会
            boolean lowSuccessRate = metrics.getSuccessRate() < MIN_SUCCESS_RATE;
            boolean lowAvgScore = metrics.getAverageScore() < MIN_AVG_SCORE;
            boolean lowReuseRate = metrics.getAverageReusePerExperience() < 0.3;

            if (!lowSuccessRate && !lowAvgScore && !lowReuseRate) {
                log.debug("认知调度器-指标健康，无可执行改进");
                return;
            }

            // 3. 优先级排序
            String priority = determinePriority(lowSuccessRate, lowAvgScore, lowReuseRate);
            log.info("认知调度器-识别到改进机会: priority={}, successRate={}, avgScore={}, reuseRate={}",
                    priority, metrics.getSuccessRate(), metrics.getAverageScore(),
                    metrics.getAverageReusePerExperience());

            // 4. 触发自学习（受控模式）
            triggerSelfLearning(priority, metrics);

        } catch (Exception e) {
            log.error("认知调度器-循环失败", e);
        }
        log.debug("认知调度器-循环完成");
    }

    /**
     * 确定改进优先级。
     */
    private String determinePriority(boolean lowSuccessRate, boolean lowAvgScore, boolean lowReuseRate) {
        if (lowSuccessRate) return "high";
        if (lowAvgScore) return "medium";
        if (lowReuseRate) return "low";
        return "none";
    }

    /**
     * 触发自学习（受控模式：离线 T+1 + 人工审核）。
     */
    private void triggerSelfLearning(String priority, LearningMetricsService.LearningMetrics metrics) {
        switch (priority) {
            case "high" -> {
                log.warn("认知调度器-高优先级: 成功率低于阈值，触发即时蒸馏评估");
                // 高优先级：立即触发蒸馏（评估现有经验是否可固化）
                List<Experience> recentExperiences = experienceMapper.selectList(
                        new LambdaQueryWrapper<Experience>()
                                .eq(Experience::getDeleted, 0)
                                .orderByDesc(Experience::getCreatedAt)
                                .last("LIMIT 10"));
                for (Experience exp : recentExperiences) {
                    if (exp.getScore() != null && exp.getScore().compareTo(BigDecimal.valueOf(0.85)) >= 0) {
                        distillationPipeline.approveAndSolidify(exp.getId());
                    }
                }
            }
            case "medium" -> {
                log.info("认知调度器-中优先级: 平均评分偏低，标记待蒸馏");
            }
            case "low" -> {
                log.info("认知调度器-低优先级: 经验复用率低，建议增加检索范围");
            }
        }
    }
}