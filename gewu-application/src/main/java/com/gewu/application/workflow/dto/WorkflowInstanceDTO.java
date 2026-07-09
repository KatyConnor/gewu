package com.gewu.application.workflow.dto;

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
    private Long startedAt;
    private Long completedAt;
    private Long createdAt;
}
