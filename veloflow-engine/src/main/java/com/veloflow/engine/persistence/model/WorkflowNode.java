package com.veloflow.engine.persistence.model;

import com.baomidou.mybatisplus.annotation.TableName;
import com.veloflow.engine.commons.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("VLF_WORKFLOW_NODE")
public class WorkflowNode extends BaseEntity {

    private String workflowId;
    /** 业务节点 ID（画布定义的 nodeId，表达式引用键） */
    private String bizNodeId;
    private String nodeName;
    private String nodeType;
    private String config;
    private Float positionX;
    private Float positionY;
    private Integer sortOrder;
}