package com.gewu.application.agent.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class McpToolDTO {
    private String name;
    private String description;
    private String inputSchema;
    private String serverId;
    private String serverName;
}
