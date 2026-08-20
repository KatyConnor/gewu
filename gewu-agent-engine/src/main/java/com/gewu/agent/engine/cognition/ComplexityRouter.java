package com.gewu.agent.engine.cognition;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 复杂度路由器 - 统一的任务复杂度评估与 System 1/2 路由。
 * <p>基于感知引擎产出的 {@link PerceptionEngine.Intent} + 描述特征计算复杂度，
 * 委托 {@link DualSystemRouter} 做 System 1/2 决策。
 * <p>路由结果供 {@code ReactAgentExecutor} 切换运行时模式和模型等级。
 *
 * @since 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class ComplexityRouter {

    private final DualSystemRouter dualSystemRouter;

    /**
     * 路由：基于任务描述和感知输出计算复杂度并决策 System 1/2。
     *
     * @param taskDescription 任务描述
     * @param intent          感知引擎产出的意图（可为 null）
     * @return 复杂度路由结果
     */
    public ComplexityResult route(String taskDescription, PerceptionEngine.Intent intent) {
        String intentType = intent != null ? intent.getIntentType() : "unknown";
        int entityCount = intent != null ? intent.getEntityCount() : 0;
        boolean requiresMultiStep = intent != null && intent.isRequiresMultiStep();

        // 委托 DualSystemRouter 做决策
        DualSystemRouter.SystemChoice systemChoice = dualSystemRouter.route(
                taskDescription, intentType, entityCount, requiresMultiStep);

        // 综合计算复杂度评分
        int score = assessComplexity(taskDescription, intentType, entityCount, requiresMultiStep);
        String level = scoreToLevel(score, systemChoice);

        log.debug("ComplexityRouter.route: intentType={}, entities={}, multiStep={} -> score={}, level={}, system={}",
                intentType, entityCount, requiresMultiStep, score, level, systemChoice.getSystem());

        return ComplexityResult.builder()
                .score(score)
                .level(level)
                .systemChoice(systemChoice)
                .build();
    }

    /**
     * 简化路由：仅基于任务描述（无感知引擎）。
     */
    public ComplexityResult route(String taskDescription) {
        return route(taskDescription, null);
    }

    private int assessComplexity(String description, String intentType, int entityCount, boolean multiStep) {
        int score = 0;

        // 意图类型权重
        if ("greeting".equals(intentType) || "faq".equals(intentType)) {
            score += 1;
        } else if ("design".equals(intentType) || "deploy".equals(intentType)) {
            score += 5;
        } else if ("code_gen".equals(intentType) || "analysis".equals(intentType)) {
            score += 4;
        } else if ("task_query".equals(intentType)) {
            score += 3;
        } else {
            score += 2;
        }

        // 实体数量
        if (entityCount > 10) score += 3;
        else if (entityCount > 5) score += 2;
        else if (entityCount > 0) score += 1;

        // 多步标志
        if (multiStep) score += 2;

        // 描述特征
        if (description != null) {
            String lower = description.toLowerCase();
            if (lower.contains("架构") || lower.contains("重构")) score += 2;
            if (description.length() > 500) score += 2;
            else if (description.length() > 200) score += 1;
        }

        return Math.min(score, 10);
    }

    private String scoreToLevel(int score, DualSystemRouter.SystemChoice choice) {
        if (choice.isSystem1()) return "L1";
        if (score >= 7) return "L3";
        return "L2";
    }

    /**
     * 复杂度路由结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComplexityResult {
        /** 复杂度评分 (1-10) */
        private int score;
        /** 任务等级: L1/L2/L3 */
        private String level;
        /** System 1/2 选择 */
        private DualSystemRouter.SystemChoice systemChoice;
    }
}