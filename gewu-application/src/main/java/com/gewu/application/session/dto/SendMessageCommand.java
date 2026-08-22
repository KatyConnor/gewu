package com.gewu.application.session.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class SendMessageCommand {

    private String messageType = "text";

    @NotBlank(message = "消息内容不能为空")
    private String content;

    private String replyTo;
    private List<String> mentionUserIds;

    /** 客户端幂等 ID：同一次发送动作的重复请求返回已有消息，网络重试时复用 */
    private String clientId;
}
