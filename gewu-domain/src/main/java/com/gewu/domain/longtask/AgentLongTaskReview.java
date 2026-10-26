package com.gewu.domain.longtask;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 长任务人工审核记录（docs/design/45 §7）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_long_task_review")
public class AgentLongTaskReview extends BaseEntity {

    public static final String KIND_GOAL_REACHED = "goal_reached";
    public static final String KIND_MILESTONE = "milestone";
    public static final String KIND_MANUAL = "manual";
    public static final String KIND_BLOCKED = "blocked";

    public static final String VERDICT_PENDING = "pending";
    public static final String VERDICT_APPROVED = "approved";
    public static final String VERDICT_REJECTED = "rejected";

    private String taskId;
    private Integer segmentSeq;
    private String kind;
    private String verdict;
    /** 审核意见（驳回时注入下一段种子） */
    private String comment;
    private String reviewer;
    private Long decidedAt;
}
