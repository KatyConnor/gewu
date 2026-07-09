package com.gewu.application.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowNotificationDTO {

    private String notificationId;
    private String instanceId;
    private String nodeInstanceId;
    private String type;
    private String recipientId;
    private String title;
    private String content;
    private Integer isRead;
    private Long sentAt;
}
