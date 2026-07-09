package com.gewu.infrastructure.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmChunk {

    private String delta;
    private ToolCallDelta toolCallDelta;
    private String finishReason;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallDelta {
        private String id;
        private String name;
        private String arguments;
    }
}
