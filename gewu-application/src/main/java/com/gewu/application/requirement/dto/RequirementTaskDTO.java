package com.gewu.application.requirement.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 需求任务 DTO.
 */
@Data
@Builder
public class RequirementTaskDTO {

    private String id;
    private String requirementId;
    private String taskCode;
    private String title;
    private String description;
    private String assigneeId;
    private String assigneeName;
    private String status;
    private String statusDesc;
    private Integer estimatedHours;
    private Integer actualHours;
    private Long startedAt;
    private Long completedAt;
    private Long createdAt;
    private String createdBy;
}
