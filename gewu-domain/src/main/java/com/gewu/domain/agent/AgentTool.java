package com.gewu.domain.agent;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_tool")
public class AgentTool extends BaseEntity {

    private String agentId;
    private String toolName;
    private String description;
    private String toolType;
    private String endpoint;
    private String requestSchema;
    private String responseSchema;
    private String authConfig;
    private Integer timeoutMs;
    private Integer status;
    private Integer sortOrder;
}