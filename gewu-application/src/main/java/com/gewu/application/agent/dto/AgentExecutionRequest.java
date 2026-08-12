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
    /** 模型 ID（如 qwen-plus），当 agentId 为 null 时用于直接对话 */
    private String model;
    /** Agent 模式: assistant/expert/creative/precise */
    private String agentMode;
    /** 思维模式: chain-of-thought/tree-of-thought/react/step-by-step/socratic */
    private String thinkingStyle;
}
