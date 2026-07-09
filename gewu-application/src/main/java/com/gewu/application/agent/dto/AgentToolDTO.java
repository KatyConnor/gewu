package com.gewu.application.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentToolDTO {

    private String toolId;
    private String agentId;
    private String toolName;
    private String description;
    private String toolType;
    private String endpoint;
    private Integer timeoutMs;
    private Integer status;
    private Integer sortOrder;
}
