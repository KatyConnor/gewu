package com.gewu.application.requirement.dto;

import lombok.Data;

/**
 * 创建需求命令.
 */
@Data
public class CreateRequirementCommand {

    private String title;
    private String description;
    private String type;
    private Integer priority;
    private String projectId;
    private String assigneeId;
    private String parentId;
    private Long dueDate;
    private Integer storyPoint;
    private Integer estimatedHours;
    private String sessionIds;
    private String documentIds;
}
