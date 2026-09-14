package com.gewu.application.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentChunk {

    private String type;
    private String content;
    /** 推理/思考内容（thinking 事件） */
    private String reasoning;
    private ToolCallInfo toolCall;
    private ToolResultInfo toolResult;
    private String errorMessage;
    /** LLM 完成原因（done 事件透传：stop / length 截断标记） */
    private String finishReason;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallInfo {
        private String id;
        private String name;
        private String arguments;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolResultInfo {
        private String toolCallId;
        private String name;
        private String result;
    }
}
