package com.gewu.application.requirement.dto;

import lombok.Data;

/**
 * 更新任务命令.
 */
@Data
public class UpdateTaskCommand {

    private String title;
    private String description;
    private String assigneeId;
    private String status;
    private Integer actualHours;
}
