package com.gewu.application.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentExecutionDTO {

    private String executionId;
    private String agentId;
    private String sessionId;
    private String userId;
    private String status;
    private String input;
    private String output;
    private String errorMessage;
    private Integer tokensUsed;
    private Long startedAt;
    private Long completedAt;
    private Integer durationMs;
}
