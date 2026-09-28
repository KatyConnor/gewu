package com.veloflow.engine.persistence.model;

import com.baomidou.mybatisplus.annotation.TableName;
import com.veloflow.engine.commons.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Veloflow Webhook 触发配置（每流程一条，token 只存 SM3 哈希） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("VLF_WORKFLOW_WEBHOOK")
public class WorkflowWebhook extends BaseEntity {

    private String workflowId;
    private String tokenHash;
    private Integer enabled;
}
