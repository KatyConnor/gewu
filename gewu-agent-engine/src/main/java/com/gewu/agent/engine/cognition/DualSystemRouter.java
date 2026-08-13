package com.gewu.agent.engine.cognition;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 双系统思考路由器 - 根据任务复杂度动态选择 System 1（快速）或 System 2（深度）模式。
 * <p>规则层 + 轻量分类，避免循环依赖。
 * <ul>
 *   <li>System 1 快速模式：简单/确定性任务，少推理直接行动，小模型，毫秒级响应</li>
 *   <li>System 2 深度模式：复杂/开放性任务，显式规划+多方案推演+反思，强模型，分钟级响应</li>
 * </ul>
 * 对齐架构文档双系统理论（来自 S5/S6）。
 *
 * @since 1.0.0
 */
@Slf4j
public class DualSystemRouter {

    /** 复杂度阈值：≤2 走 System 1，≥6 走 System 2 深度 */
    private static final int SYSTEM1_THRESHOLD = 2;
    private static final int SYSTEM2_THRESHOLD = 6;

    /**
     * 路由决策：根据任务特征决定走 System 1 还是 System 2。
     *
     * @param taskDescription 任务描述
     * @param intentType      意图类型（greeting/faq/query/analysis/design 等）
     * @param entityCount     实体数量
     * @param requiresMultiStep 是否需要多步执行
     * @return 系统模式决策
     */
    public SystemChoice route(String taskDescription, String intentType, int entityCount, boolean requiresMultiStep) {
        // 规则层：确定性、零成本判断
        if (isSimpleIntent(intentType)) {
            log.debug("DualSystemRouter: System 1 (简单意图: {})", intentType);
            return SystemChoice.system1();
        }

        if (requiresMultiStep || entityCount > 10) {
            log.debug("DualSystemRouter: System 2 (多步/高复杂度: entities={}, multiStep={})",
                    entityCount, requiresMultiStep);
            return SystemChoice.system2();
        }

        // 轻量分类：基于描述长度和关键词
        int complexity = assessComplexity(taskDescription);
        if (complexity <= SYSTEM1_THRESHOLD) {
            log.debug("DualSystemRouter: System 1 (低复杂度: {})", complexity);
            return SystemChoice.system1();
        } else if (complexity >= SYSTEM2_THRESHOLD) {
            log.debug("DualSystemRouter: System 2 (高复杂度: {})", complexity);
            return SystemChoice.system2();
        } else {
            log.debug("DualSystemRouter: System 2 (中等复杂度，默认深度: {})", complexity);
            return SystemChoice.system2();
        }
    }

    /**
     * 简化路由：仅基于任务描述。
     */
    public SystemChoice route(String taskDescription) {
        return route(taskDescription, "unknown", 0, false);
    }

    private boolean isSimpleIntent(String intentType) {
        if (intentType == null) return false;
        return switch (intentType) {
            case "greeting", "faq", "chitchat", "confirmation" -> true;
            default -> false;
        };
    }

    /**
     * 轻量复杂度评估（规则 + 描述特征，不调用 LLM）。
     */
    private int assessComplexity(String description) {
        if (description == null || description.isBlank()) return 1;
        int score = 0;
        // 描述长度
        if (description.length() > 500) score += 3;
        else if (description.length() > 200) score += 2;
        else if (description.length() > 50) score += 1;

        // 复杂关键词
        String lower = description.toLowerCase();
        if (lower.contains("架构") || lower.contains("设计") || lower.contains("重构"))
            score += 3;
        if (lower.contains("分析") || lower.contains("评估") || lower.contains("对比"))
            score += 2;
        if (lower.contains("生成") || lower.contains("创建") || lower.contains("实现"))
            score += 2;
        if (lower.contains("查询") || lower.contains("搜索") || lower.contains("查找"))
            score += 1;

        return Math.min(score, 10);
    }

    // ===== DTO =====

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SystemChoice {
        /** 系统模式: SYSTEM_1 / SYSTEM_2 */
        private String system;
        /** 运行时模式: REACT / PLAN_EXECUTE / REFLEXION */
        private String runtimeMode;
        /** 模型等级推荐: 1(轻量) / 2(标准) / 3(强力) */
        private int modelTier;

        public static SystemChoice system1() {
            return new SystemChoice("SYSTEM_1", "REACT", 1);
        }

        public static SystemChoice system2() {
            return new SystemChoice("SYSTEM_2", "PLAN_EXECUTE", 3);
        }

        public boolean isSystem1() {
            return "SYSTEM_1".equals(system);
        }
    }
}