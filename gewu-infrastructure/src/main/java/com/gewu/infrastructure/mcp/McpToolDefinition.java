package com.gewu.infrastructure.mcp;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class McpToolDefinition {
    private String name;
    private String description;
    private String inputSchema;
}
