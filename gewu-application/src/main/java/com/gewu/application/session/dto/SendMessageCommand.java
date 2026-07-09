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
}
