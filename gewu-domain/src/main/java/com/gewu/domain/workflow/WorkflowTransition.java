package com.gewu.domain.workflow;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseSimpleEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_transition")
public class WorkflowTransition extends BaseSimpleEntity {

    private String workflowId;
    private String fromNodeId;
    private String toNodeId;
    private String conditionExpr;
    private String label;
    private Integer sortOrder;
}