package com.gewu.application.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    private String messageId;
    private String content;
    private List<ToolCallInfo> toolCalls;
    private UsageInfo usage;
    private String finishReason;
}
