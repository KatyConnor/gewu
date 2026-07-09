package com.gewu.application.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowDTO {

    private String workflowId;
    private String workflowName;
    private String description;
    private Integer version;
    private Integer status;
    private String statusDesc;
    private String category;
    private String config;
    private Long publishedAt;
    private Long createdAt;
    private String createdBy;
    private Long nodeCount;
}
