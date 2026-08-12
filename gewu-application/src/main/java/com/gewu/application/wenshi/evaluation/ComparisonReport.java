package com.gewu.application.wenshi.evaluation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 对比报告 — 多基线、多场景的评估汇总报告。
 * <p>
 * 包含原始结果列表和各基线的统计摘要，用于直观对比不同基线的综合能力。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComparisonReport {

    /** 报告唯一标识 */
    private String reportId;
    /** 报告生成时间（ISO-8601 格式） */
    private String generatedAt;
    /** 参与对比的基线名称列表 */
    private List<String> baselineNames;
    /** 参与评估的场景 ID 列表 */
    private List<String> scenarioIds;
    /** 所有评估结果明细 */
    private List<EvaluationResult> results;
    /** 各基线的统计摘要，key 为基线名称 */
    private Map<String, BaselineSummary> summaries;

    /**
     * 基线摘要 — 单个基线的评估统计汇总。
     *
     * @since 1.0.0
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BaselineSummary {
        /** 基线名称 */
        private String baselineName;
        /** 总场景数 */
        private int totalScenarios;
        /** 成功场景数 */
        private int successCount;
        /** 成功率（0~1） */
        private double successRate;
        /** 平均得分 */
        private double avgScore;
        /** 平均耗时（毫秒） */
        private double avgLatencyMs;
        /** 平均 token 消耗 */
        private double avgTokens;
        /** 平均成本估算 */
        private double avgCost;
        /** 平均 LLM 调用次数 */
        private double avgLlmCalls;
        /** 经验复用率（0~1） */
        private double experienceReuseRate;
    }
}
