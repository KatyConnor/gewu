package com.gewu.domain.session;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("session_message")
public class SessionMessage extends BaseEntity {

    private String sessionId;
    private String senderId;
    private String messageType;
    private String content;
    private String metadata;
    private String replyTo;
    private String mentionUserIds;
    private String clientId;
    private Integer seq;
    private Integer edited;
}