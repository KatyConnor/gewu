package com.gewu.application.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowNodeDTO {

    private String nodeId;
    private String workflowId;
    private String nodeName;
    private String nodeType;
    private String config;
    private Float positionX;
    private Float positionY;
    private Integer sortOrder;
}
