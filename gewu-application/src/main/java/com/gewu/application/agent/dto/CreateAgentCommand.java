package com.gewu.application.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateAgentCommand {

    @NotBlank(message = "Agent 名称不能为空")
    @Size(max = 128, message = "Agent 名称最长 128 个字符")
    private String agentName;

    @Size(max = 1024, message = "描述最长 1024 个字符")
    private String description;

    @NotBlank(message = "模型提供商不能为空")
    @Size(max = 64, message = "模型提供商最长 64 个字符")
    private String modelProvider;

    @NotBlank(message = "模型名称不能为空")
    @Size(max = 128, message = "模型名称最长 128 个字符")
    private String modelName;

    private String modelConfig;

    private String systemPrompt;

    private Integer status = 1;
}
