package com.gewu.application.agent.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateAgentCommand {

    @Size(max = 128, message = "Agent 名称最长 128 个字符")
    private String agentName;

    @Size(max = 1024, message = "描述最长 1024 个字符")
    private String description;

    @Size(max = 64, message = "模型提供商最长 64 个字符")
    private String modelProvider;

    @Size(max = 128, message = "模型名称最长 128 个字符")
    private String modelName;

    private String modelConfig;

    private String systemPrompt;

    private Integer status;
}
