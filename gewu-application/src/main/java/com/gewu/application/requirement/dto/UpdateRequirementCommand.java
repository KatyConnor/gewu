package com.gewu.application.requirement.dto;

import lombok.Data;

/**
 * 更新需求命令.
 */
@Data
public class UpdateRequirementCommand {

    private String title;
    private String description;
    private String type;
    private Integer priority;
    private String assigneeId;
    private String designerId;
    private String developerId;
    private String testerId;
    private Long dueDate;
    private Integer storyPoint;
    private Integer estimatedHours;
    private String sessionIds;
    private String documentIds;
    private String designDoc;
    private String planDoc;
    private String testDoc;
    private String cancelReason;
}
