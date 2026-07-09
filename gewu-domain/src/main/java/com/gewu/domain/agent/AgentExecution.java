package com.gewu.domain.agent;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_execution")
public class AgentExecution extends BaseEntity {

    private String agentId;
    private String sessionId;
    private String userId;
    private String status;
    private String input;
    private String output;
    private String errorMessage;
    private String toolCalls;
    private Integer tokensUsed;
    private Long startedAt;
    private Long completedAt;
    private Integer durationMs;
}