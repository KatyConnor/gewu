package com.veloflow.engine.definition.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 定义版本快照元数据（51 号 T4.4） */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowVersionDTO {

    private String versionId;
    private String workflowId;
    private Integer version;
    private String publishedBy;
    private Long publishedAt;
}
