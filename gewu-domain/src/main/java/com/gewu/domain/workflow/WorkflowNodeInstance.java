package com.gewu.domain.workflow;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_node_instance")
public class WorkflowNodeInstance extends BaseEntity {

    private String instanceId;
    private String nodeId;
    private String nodeName;
    private String nodeType;
    private String status;
    private String assigneeId;
    private String input;
    private String output;
    private Long startedAt;
    private Long completedAt;
    private String remark;
}