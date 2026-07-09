package com.gewu.infrastructure.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmRequest {

    private String model;
    private List<Message> messages;
    private List<ToolDefinition> tools;
    private Double temperature;
    private Integer maxTokens;
    private Boolean stream;
}
