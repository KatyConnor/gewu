package com.veloflow.engine.persistence.model;

import com.baomidou.mybatisplus.annotation.TableName;
import com.veloflow.engine.commons.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("VLF_WORKFLOW_AUDIT_LOG")
public class WorkflowAuditLog extends BaseEntity {

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