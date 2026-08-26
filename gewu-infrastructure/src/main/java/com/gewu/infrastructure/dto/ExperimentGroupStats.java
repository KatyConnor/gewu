package com.gewu.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 实验分组统计（A/B 对比报表行，T3.4）。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExperimentGroupStats {

    /** 实验分组标识 */
    private String experimentGroup;
    /** 样本量（执行次数） */
    private Long totalCount;
    /** 成功次数 */
    private Long successCount;
    /** 成功率（0-1） */
    private Double successRate;
    /** 平均执行时长（毫秒） */
    private Double avgDurationMs;
    /** 平均 token 消耗 */
    private Double avgTokens;
    /** LLM-as-Judge 平均分（无评测记录为 null） */
    private Double avgJudgeScore;
    /** 评测样本量 */
    private Long judgedCount;
}
