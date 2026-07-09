package com.gewu.application.session.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MessageDTO {

    private String messageId;
    private String sessionId;
    private String senderId;
    private String senderName;
    private String messageType;
    private String content;
    private String replyTo;
    private Integer seq;
    private Integer edited;
    private Long createdAt;
}
