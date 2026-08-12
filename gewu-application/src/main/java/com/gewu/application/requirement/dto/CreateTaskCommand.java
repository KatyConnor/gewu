package com.gewu.application.requirement.dto;

import lombok.Data;

/**
 * 创建任务命令.
 */
@Data
public class CreateTaskCommand {

    private String title;
    private String description;
    private String assigneeId;
    private Integer estimatedHours;
}
