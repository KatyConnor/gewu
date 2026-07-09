package com.gewu.application.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowTransitionDTO {

    private String transitionId;
    private String workflowId;
    private String fromNodeId;
    private String toNodeId;
    private String conditionExpr;
    private String label;
    private Integer sortOrder;
}
