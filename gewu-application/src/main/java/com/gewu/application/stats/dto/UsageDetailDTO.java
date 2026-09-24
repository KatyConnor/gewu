package com.gewu.application.stats.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 用量明细统计响应：tokens/成本（按模型分组与合计）+ 消息数（用户/智能体/合计），
 * 按 granularity 归并为时间序列。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageDetailDTO {

    /** 粒度: day / week / month / quarter / year */
    private String granularity;
    /** 统计范围起点（毫秒） */
    private Long from;
    /** 统计范围终点（毫秒） */
    private Long to;
    /** 数据范围：SELF=本人 / ALL=全局（管理员） */
    private String scope;

    /** tokens/成本时间序列（按时间升序，空桶零填充） */
    private List<BucketUsage> tokensSeries;

    /** 消息数时间序列（按时间升序，空桶零填充） */
    private List<BucketMessages> messageSeries;

    /** 范围内合计（tokens/成本，按模型分组 + 总合计） */
    private List<ModelTotal> modelTotals;
    private long totalInputTokens;
    private long totalOutputTokens;
    private long totalTokens;
    private double totalCost;
    private long totalUserMessages;
    private long totalAgentMessages;
    private long totalMessages;

    /** 单时间桶的用量（含按模型分组） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BucketUsage {
        /** 桶标签（如 2026-09-19 / 2026-09 / 2026-Q3） */
        private String bucket;
        private long inputTokens;
        private long outputTokens;
        private long totalTokens;
        private double cost;
        /** 按模型分组 */
        private List<ModelTotal> models;
    }

    /** 单时间桶的消息数 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BucketMessages {
        private String bucket;
        private long userMessages;
        private long agentMessages;
        private long total;
    }

    /** 按模型分组的合计 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelTotal {
        private String modelId;
        private long inputTokens;
        private long outputTokens;
        private long totalTokens;
        private double cost;
    }
}
