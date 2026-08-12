package com.gewu.common.dto.sandbox;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ExecCommandRequest {

    @NotBlank(message = "命令不能为空")
    private String command;

    private Integer timeout;
}
