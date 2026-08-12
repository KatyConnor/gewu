package com.gewu.application.wenshi.evaluation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 评估结果 — 记录单次评估执行的详细数据。
 * <p>
 * 包含执行结果、性能指标、资源消耗和扩展指标，用于横向对比不同基线的能力。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationResult {

    /** 关联的场景 ID */
    private String scenarioId;
    /** 基线名称（如 Wenshi+DeepSeek、GPT-4） */
    private String baselineName;
    /** 是否执行成功 */
    private boolean success;
    /** 评估得分（0~1） */
    private double score;
    /** 执行耗时（毫秒） */
    private long latencyMs;
    /** Prompt token 数 */
    private int promptTokens;
    /** Completion token 数 */
    private int completionTokens;
    /** 总 token 数 */
    private int totalTokens;
    /** LLM 调用次数 */
    private int llmCallCount;
    /** 是否来自经验复用 */
    private boolean fromExperience;
    /** 推理步骤详情 */
    private String traceSteps;
    /** 错误信息（失败时） */
    private String errorMessage;
    /** 扩展指标，支持自定义维度 */
    private Map<String, Object> extraMetrics;

    /**
     * 估算本次评估的 token 成本。
     * <p>
     * 按每百万 token 0.002 元估算。
     *
     * @return 估算成本（元）
     * @since 1.0.0
     */
    public double getCostEstimate() {
        return totalTokens * 0.000002;
    }
}
