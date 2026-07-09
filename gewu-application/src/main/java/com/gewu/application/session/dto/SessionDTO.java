package com.gewu.application.session.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SessionDTO {

    private String sessionId;
    private String title;
    private Integer type;
    private String typeDesc;
    private String projectId;
    private Integer status;
    private String statusDesc;
    private Integer isPublic;
    private Integer messageCount;
    private Long lastMessageAt;
    private String agent;
    private String directory;
    private Long createdAt;
    private String createdBy;
}
