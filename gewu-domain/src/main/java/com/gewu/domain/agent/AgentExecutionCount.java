package com.gewu.domain.agent;

import lombok.Data;

/**
 * Agent 执行次数统计结果（按 agent_id 分组计数）.
 */
@Data
public class AgentExecutionCount {
    private String agentId;
    private Integer cnt;
}
