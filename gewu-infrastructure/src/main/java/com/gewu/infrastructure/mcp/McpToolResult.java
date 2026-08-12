package com.gewu.infrastructure.mcp;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class McpToolResult {
    private boolean success;
    private String output;
    private String error;
}
