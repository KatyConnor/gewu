package com.gewu.application.session.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EditMessageCommand {

    @NotBlank(message = "消息内容不能为空")
    private String content;
}
