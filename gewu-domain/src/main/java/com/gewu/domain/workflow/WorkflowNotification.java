package com.gewu.domain.workflow;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseSimpleEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_notification")
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