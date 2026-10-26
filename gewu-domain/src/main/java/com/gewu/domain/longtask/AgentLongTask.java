package com.gewu.domain.longtask;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 持久化目标驱动长任务（docs/design/45）：小时级～按天、目标达标即止、人工审核闭环。
 * <p>状态机：QUEUED → RUNNING ⇄ (GOAL_EVAL/WAITING_REVIEW/PAUSED) → COMPLETED；
 * 另有 BLOCKED / FAILED / EXPIRED / CANCELLED。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_long_task")
public class AgentLongTask extends BaseEntity {

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_WAITING_REVIEW = "WAITING_REVIEW";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_BLOCKED = "BLOCKED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private String sessionId;
    private String title;
    /** 目标声明（自然语言） */
    private String goalText;
    /** 验收标准 JSON（statement/checks/review_required/milestone_every_segments） */
    private String criteriaJson;
    /** 达标后是否必须人工审核 */
    private Integer reviewRequired;
    /** 段执行使用的智能体（继承会话；空=引擎默认） */
    private String agentId;
    /** 目标判官 LLM 供应商（空=跳过 llm_judge） */
    private String providerCode;
    /** 目标判官模型 */
    private String model;
    private String status;
    private Integer segmentCount;
    private Integer maxSegments;
    private Integer maxDays;
    /** deadline（毫秒时间戳） */
    private Long deadlineAt;
    private Integer cooldownMinutes;
    /** 日 token 上限（0=不限；按自然日累计） */
    private Long dailyTokenCap;
    private Long totalTokens;
    /** 连续无进展段数（≥阈值 → BLOCKED） */
    private Integer failStreak;
    /** 下一段调度时间（毫秒） */
    private Long nextRunAt;
    /** 待下一段消费的用户补充指令 */
    private String steeringNote;
    /** 最新目标进度摘要（列表页直读） */
    private String progressSummary;
    private String createdBy;
}
