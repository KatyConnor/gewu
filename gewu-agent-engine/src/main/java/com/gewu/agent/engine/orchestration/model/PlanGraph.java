package com.gewu.agent.engine.orchestration.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 计划图 - 逻辑层任务描述（人类可理解，不含技术细节）。
 * <p>与 {@link ExecutionGraph} 分离，实现"逻辑-技术"双图解耦。
 * 当技术实现变化时只需修改执行图，计划图保持不变。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanGraph {

    /** 计划图 ID */
    private String planId;
    /** 目标描述 */
    private String goal;
    /** 计划步骤（逻辑层，描述性） */
    private List<PlanStep> steps;
    /** 创建者 */
    private String createdBy;

    /**
     * 计划步骤 - 人类可理解的任务描述。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanStep {
        /** 步骤 ID */
        private String stepId;
        /** 步骤描述（自然语言） */
        private String description;
        /** 依赖的前置步骤 ID */
        private List<String> dependencies;
        /** 执行角色编码 */
        private String roleCode;
        /** 预估复杂度 */
        private int estimatedComplexity;
    }
}