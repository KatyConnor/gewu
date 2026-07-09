package com.gewu.domain.workflow;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseSimpleEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_permission_matrix")
public class WorkflowPermissionMatrix extends BaseSimpleEntity {

    private String workflowId;
    private String nodeType;
    private String requiredRole;
    private String permissionLevel;
}