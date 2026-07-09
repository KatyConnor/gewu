package com.gewu.domain.workflow;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseSimpleEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_audit_log")
public class WorkflowAuditLog extends BaseSimpleEntity {

    private String workflowId;
    private String instanceId;
    private String nodeId;
    private String operation;
    private String operatorId;
    private String operatorName;
    private String beforeState;
    private String afterState;
    private String ipAddress;
    private String requestBody;
    private Integer responseCode;
    private Long responseTime;
}