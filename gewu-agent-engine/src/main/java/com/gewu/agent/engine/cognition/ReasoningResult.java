package com.gewu.agent.engine.cognition;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 推理结果 - ReasoningKernel 产出的规划 / 求解 / 评估结果。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReasoningResult {

    /** 推理类型：PLAN / SOLVE / CRITIC */
    private String type;
    /** 规划的子任务列表（PLAN 类型） */
    private List<String> subtasks;
    /** 求解答案（SOLVE 类型） */
    private String answer;
    /** 评估结论（CRITIC 类型） */
    private String verdict;
    /** 评估评分（CRITIC 类型, 0~1） */
    private double score;
    /** 是否通过验收（CRITIC 类型） */
    private boolean accepted;
    /** 推理过程摘要 */
    private String reasoning;
}