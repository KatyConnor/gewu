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
    private String nodeName;
    private String nodeType;
    private String config;
    private Float positionX;
    private Float positionY;
    private Integer sortOrder;
}