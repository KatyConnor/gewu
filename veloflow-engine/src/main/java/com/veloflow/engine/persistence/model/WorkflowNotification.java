package com.veloflow.engine.persistence.model;

import com.baomidou.mybatisplus.annotation.TableName;
import com.veloflow.engine.commons.BaseSimpleEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("VLF_WORKFLOW_NOTIFICATION")
public class WorkflowNotification extends BaseSimpleEntity {

    private String instanceId;
    private String nodeInstanceId;
    private String type;
    private String recipientId;
    private String title;
    private String content;
    private Integer isRead;
    private Long sentAt;
}