package com.gewu.application.wenshi.evaluation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 评估场景 — 定义评估用例的基本信息。
 * <p>
 * 每个场景代表一类典型任务，包含预期结果、难度等级和参数配置。
 * 可通过 {@link #standardScenarios()} 获取预定义的 6 类标准评估场景。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationScenario {

    /** 场景唯一标识 */
    private String id;
    /** 场景名称 */
    private String name;
    /** 场景分类（如 KNOWLEDGE_QA、DATA_QUERY） */
    private String category;
    /** 难度等级（简单/中等/复杂/困难） */
    private String difficulty;
    /** 场景描述 */
    private String description;
    /** 预期结果 */
    private String expectedOutcome;
    /** 标签列表，用于分类筛选 */
    private List<String> tags;
    /** 扩展参数，支持自定义评估配置 */
    private Map<String, Object> parameters;

    /**
     * 获取预定义的标准评估场景列表。
     * <p>
     * 覆盖 6 类典型任务：知识问答、数据查询、数据分析、流程执行、异常处理、多轮对话。
     *
     * @return 标准评估场景列表
     * @since 1.0.0
     */
    public static List<EvaluationScenario> standardScenarios() {
        return List.of(
                EvaluationScenario.builder()
                        .id("qa-001").name("知识问答").category("KNOWLEDGE_QA").difficulty("简单")
                        .description("基于知识库的事实性问题")
                        .expectedOutcome("准确回答事实性问题")
                        .tags(List.of("qa", "knowledge")).build(),

                EvaluationScenario.builder()
                        .id("query-001").name("数据查询").category("DATA_QUERY").difficulty("中等")
                        .description("查询指定时间范围内的业务数据")
                        .expectedOutcome("返回正确的数据查询结果")
                        .tags(List.of("query", "data")).build(),

                EvaluationScenario.builder()
                        .id("analysis-001").name("数据分析").category("DATA_ANALYSIS").difficulty("复杂")
                        .description("对业务数据做汇总分析并生成报告")
                        .expectedOutcome("生成准确的分析报告")
                        .tags(List.of("analysis", "report")).build(),

                EvaluationScenario.builder()
                        .id("process-001").name("流程执行").category("PROCESS_EXECUTION").difficulty("中等")
                        .description("按 SOP 执行多步骤业务流程")
                        .expectedOutcome("正确完成流程所有步骤")
                        .tags(List.of("process", "sop")).build(),

                EvaluationScenario.builder()
                        .id("error-001").name("异常处理").category("ERROR_HANDLING").difficulty("困难")
                        .description("处理执行过程中的异常情况")
                        .expectedOutcome("正确识别异常并给出恢复策略")
                        .tags(List.of("error", "recovery")).build(),

                EvaluationScenario.builder()
                        .id("multi-001").name("多轮对话").category("MULTI_TURN").difficulty("中等")
                        .description("理解上下文的多轮对话")
                        .expectedOutcome("正确理解上下文并给出连贯回答")
                        .tags(List.of("multi-turn", "context")).build()
        );
    }
}
