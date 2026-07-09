package com.gewu.sandbox.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ExecCommandRequest {

    @NotBlank(message = "命令不能为空")
    private String command;

    private Integer timeout;
}
