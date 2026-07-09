package com.gewu.domain.workflow;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_permission")
public class WorkflowPermission extends BaseEntity {

    private String workflowId;
    private String roleCode;
    private String permissionType;
}