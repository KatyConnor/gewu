package com.gewu.application.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowNodeInstanceDTO {

    private String nodeInstanceId;
    private String instanceId;
    private String nodeId;
    private String nodeName;
    private String nodeType;
    private String status;
    private String statusDesc;
    private String assigneeId;
    private String assigneeName;
    private String input;
    private String output;
    private Long startedAt;
    private Long completedAt;
    private String remark;
}
