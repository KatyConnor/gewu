package com.gewu.application.agent.dto;

import com.gewu.infrastructure.llm.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentExecutionRequest {

    private String agentId;
    private String sessionId;
    private String userId;
    private String message;
    private List<Message> history;
}
