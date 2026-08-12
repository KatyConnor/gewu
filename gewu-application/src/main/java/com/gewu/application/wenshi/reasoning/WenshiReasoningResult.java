package com.gewu.application.wenshi.reasoning;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Wenshi 推理结果对象。
 * <p>
 * 封装推理引擎的完整输出，包括最终答案、执行计划、推理轨迹、知识引用及统计信息。
 * 支持结构化输出和文本输出两种模式。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WenshiReasoningResult {

    /** 最终答案文本 */
    private String answer;

    /** 结构化输出对象（可选），用于需要结构化数据的场景 */
    private Object structuredOutput;

    /** 执行计划树，记录任务分解结果 */
    private PlanTree plan;

    /** 推理轨迹，记录每个执行步骤的详细信息 */
    private List<TraceStep> trace;

    /** 引用的知识片段 ID 列表，用于溯源 */
    private List<Long> usedKnowledgeIds;

    /** 复用的经验 ID（若命中经验库） */
    private Long reusedExperienceId;

    /** Token 消耗统计 */
    private TokenStatistics tokenStats;

    /** 推理耗时（毫秒） */
    private long reasoningTimeMs;

    /** 是否来自经验复用（跳过 LLM 推理） */
    private boolean fromExperience;

    /**
     * 执行计划树。
     * <p>
     * 表示任务分解后的层次结构，包含根任务描述和子目标列表。
     *
     * @since 1.0.0
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanTree {
        /** 根任务描述 */
        private String task;

        /** 子目标节点列表，按执行顺序排列 */
        private List<SubgoalNode> subgoals;
    }

    /**
     * 子目标节点。
     * <p>
     * 表示计划树中的一个可执行单元，包含描述、策略、依赖关系及完成状态。
     *
     * @since 1.0.0
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubgoalNode {
        /** 子目标唯一标识 */
        private String id;

        /** 子目标描述文本 */
        private String description;

        /** 推荐求解策略（KNOWLEDGE_LOOKUP / WEB_SEARCH / TOOL_EXECUTION / LLM_REASONING） */
        private String strategy;

        /** 前置依赖的子目标 ID 列表 */
        private List<String> dependencies;

        /** 是否已完成执行 */
        private boolean completed;
    }

    /**
     * 推理轨迹步骤。
     * <p>
     * 记录推理过程中每个阶段的操作详情，用于可观测性和调试。
     *
     * @since 1.0.0
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TraceStep {
        /** 步骤序号 */
        private int step;

        /** 阶段标识（PLAN / SOLVE / CRITIC） */
        private String phase;

        /** 操作名称 */
        private String action;

        /** 操作详情 */
        private String detail;

        /** 步骤时间戳 */
        private long timestamp;
    }

    /**
     * Token 消耗统计。
     * <p>
     * 记录推理过程中的 Token 使用情况，用于成本核算和性能优化。
     *
     * @since 1.0.0
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenStatistics {
        /** 输入 Token 数量 */
        private int promptTokens;

        /** 输出 Token 数量 */
        private int completionTokens;

        /** 总 Token 数量 */
        private int totalTokens;

        /** LLM 调用次数 */
        private int llmCallCount;

        /** 网络搜索次数（含自动增强触发） */
        private int webSearchCount;

        /** 网络搜索结果采纳数 */
        private int webResultsAdopted;

        /** 网络搜索结果丢弃数 */
        private int webResultsDiscarded;

        /** 估算节省的 LLM 调用次数（被网络搜索采纳结果替代） */
        private int llmCallsSaved;
    }
}
