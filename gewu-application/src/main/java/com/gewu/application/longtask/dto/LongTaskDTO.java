package com.gewu.application.longtask.dto;

import lombok.Builder;
import lombok.Data;

/** 长任务详情/列表 DTO（docs/design/45）。 */
@Data
@Builder
public class LongTaskDTO {

    private String id;
    private String sessionId;
    private String title;
    private String goalText;
    private String status;
    private Integer segmentCount;
    private Integer maxSegments;
    private Integer maxDays;
    private Long deadlineAt;
    private Integer failStreak;
    private Long nextRunAt;
    private String progressSummary;
    private Long totalTokens;
    private Long dailyTokenCap;
    private Long createdAt;
    /** 最新目标评估摘要（来自最近一段的 goal_eval） */
    private String latestEvalSummary;
}
