package com.gewu.domain.workflow;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_node")
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