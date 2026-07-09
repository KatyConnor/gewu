package com.gewu.application.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolContext {

    private String userId;
    private String sessionId;
    private String agentId;
    private int timeout;
    private boolean sandboxEnabled;
}
