package com.veloflow.engine.persistence.model;

import com.baomidou.mybatisplus.annotation.TableName;
import com.veloflow.engine.commons.BaseSimpleEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("VLF_WORKFLOW_PERMISSION_MATRIX")
public class WorkflowPermissionMatrix extends BaseSimpleEntity {

    private String workflowId;
    private String nodeType;
    private String requiredRole;
    private String permissionLevel;
}