package com.gewu.application.requirement.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 需求 DTO.
 */
@Data
@Builder
public class RequirementDTO {

    private String id;
    private String requirementCode;
    private String title;
    private String description;
    private String type;
    private String typeDesc;
    private String typeIcon;
    private Integer priority;
    private String priorityDesc;
    private String status;
    private String statusDesc;
    private String assigneeId;
    private String assigneeName;
    private String reporterId;
    private String reporterName;
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
    private Long createdAt;
    private String createdBy;
}
