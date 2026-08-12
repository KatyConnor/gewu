package com.gewu.domain.requirement;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 需求任务实体 — 需求拆分为具体开发任务.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("requirement_task")
public class RequirementTask extends BaseEntity {

    private String requirementId;
    private String taskCode;
    private String title;
    private String description;
    private String assigneeId;
    private String status;
    private Integer estimatedHours;
    private Integer actualHours;
    private Long startedAt;
    private Long completedAt;
}
