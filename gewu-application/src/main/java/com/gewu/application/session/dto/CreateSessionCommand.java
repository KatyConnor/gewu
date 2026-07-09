package com.gewu.application.session.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateSessionCommand {

    @NotBlank(message = "会话标题不能为空")
    private String title;

    @NotNull(message = "会话类型不能为空")
    private Integer type;

    private String projectId;
    private Integer isPublic;
    private String agent;
    private String directory;
}
