package com.veloflow.engine.persistence.model;

import com.baomidou.mybatisplus.annotation.TableName;
import com.veloflow.engine.commons.BaseSimpleEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("VLF_WORKFLOW_TRANSITION")
public class WorkflowTransition extends BaseSimpleEntity {

    private String workflowId;
    private String fromNodeId;
    private String toNodeId;
    private String conditionExpr;
    private String label;
    private Integer sortOrder;
}