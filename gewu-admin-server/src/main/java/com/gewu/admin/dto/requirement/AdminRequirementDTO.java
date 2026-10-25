package com.gewu.admin.dto.requirement;

import lombok.Builder;
import lombok.Data;

/**
 * 管理端需求视图.
 */
@Data
@Builder
public class AdminRequirementDTO {

    private String id;

    private String requirementCode;

    private String title;

    private String description;

    private String type;

    private Integer priority;

    private String status;

    private String assigneeId;

    private String assigneeName;

    private String reporterId;

    private String reporterName;

    private String projectId;

    private String projectName;

    private Long dueDate;

    private Integer storyPoint;

    private Integer estimatedHours;

    private Long createdAt;

    private Long startedAt;

    private Long completedAt;

    private String cancelReason;
}
