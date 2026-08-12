package com.gewu.domain.requirement;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 需求实体 — 管理需求全生命周期.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("requirement")
public class Requirement extends BaseEntity {

    private String requirementCode;
    private String title;
    private String description;
    private String type;
    private Integer priority;
    private String status;
    private String assigneeId;
    private String reporterId;
    private String designerId;
    private String developerId;
    private String testerId;
    private String parentId;
    private String projectId;
    private String sessionIds;
    private String documentIds;
    private String designDoc;
    private String planDoc;
    private String testDoc;
    private Integer storyPoint;
    private Integer estimatedHours;
    private Integer actualHours;
    private Long dueDate;
    private Long startedAt;
    private Long completedAt;
    private Long releasedAt;
    private Long cancelledAt;
    private String cancelReason;
    /** 需求开发分支 (如 feature/REQ-001) */
    private String gitBranch;
}
