package com.gewu.application.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentDTO {

    private String agentId;
    private String agentName;
    private String description;
    private String modelProvider;
    private String modelName;
    private String modelConfig;
    private String systemPrompt;
    private Integer status;
    private String statusDesc;
    private Integer version;
    private Long createdAt;
    private String createdBy;
}
