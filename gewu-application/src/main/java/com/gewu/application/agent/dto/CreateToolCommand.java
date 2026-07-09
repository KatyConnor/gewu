package com.gewu.application.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateToolCommand {

    private String agentId;

    @NotBlank(message = "工具名称不能为空")
    @Size(max = 128, message = "工具名称最长 128 个字符")
    private String toolName;

    @Size(max = 1024, message = "描述最长 1024 个字符")
    private String description;

    @NotBlank(message = "工具类型不能为空")
    @Size(max = 64, message = "工具类型最长 64 个字符")
    private String toolType;

    @Size(max = 512, message = "端点最长 512 个字符")
    private String endpoint;

    private String requestSchema;

    private String responseSchema;

    private String authConfig;

    private Integer timeoutMs = 30000;

    private Integer sortOrder = 0;
}
