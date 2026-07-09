package com.gewu.application.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatStreamEvent {

    private String type;
    private String content;
    private ToolCallInfo toolCall;
    private ToolResultInfo toolResult;
    private String errorMessage;
}
