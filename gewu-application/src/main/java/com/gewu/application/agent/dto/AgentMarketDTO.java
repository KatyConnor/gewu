package com.gewu.application.agent.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 智能体广场 DTO.
 */
@Data
@Builder
public class AgentMarketDTO {

    private String marketId;
    private String agentId;
    private String agentName;
    private String description;
    private String modelProvider;
    private String modelName;
    private String modelConfig;
    private String systemPrompt;
    private String emoji;
    private String category;
    private List<String> tags;
    private Integer stars;
    private Integer installCount;
    private String author;
    private Integer status;
    private Integer version;
    private Long createdAt;
    private String createdBy;
}
