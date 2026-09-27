package com.veloflow.engine.definition.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowInstanceDTO {

    private String instanceId;
    private String workflowId;
    private Integer workflowVersion;
    private String title;
    private String status;
    private String statusDesc;
    private String initiatorId;
    private String initiatorName;
    private String currentNodeId;
    private String currentNodeName;
    private String variables;
    /** 终态输出（return 节点产出） */
    private String finalOutput;
    /** 失败原因 */
    private String errorMessage;
    /** 触发方式: MANUAL/SCHEDULE/WEBHOOK/EVENT/UPSTREAM */
    private String triggerType;
    private Long startedAt;
    private Long completedAt;
    private Long createdAt;
}
