package com.gewu.application.agent.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class McpServerDTO {
    private String serverId;
    private String name;
    private String description;
    private String transport;
    private String command;
    private String args;
    private String url;
    private String env;
    private Integer status;
    private Long createdAt;
    private Long updatedAt;
}
