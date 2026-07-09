package com.gewu.domain.workflow;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_instance")
public class WorkflowInstance extends BaseEntity {

    private String workflowId;
    private Integer workflowVersion;
    private String title;
    private String status;
    private String initiatorId;
    private String currentNodeId;
    private String variables;
    private Long startedAt;
    private Long completedAt;
}