package com.gewu.application.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateWorkflowCommand {

    @NotBlank(message = "工作流名称不能为空")
    @Size(max = 128, message = "工作流名称最长 128 个字符")
    private String workflowName;

    @Size(max = 1024, message = "工作流描述最长 1024 个字符")
    private String description;

    private String category;

    private String config;
}
