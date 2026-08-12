package com.gewu.common.dto.sandbox;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class ExecuteCodeRequest {

    @NotBlank(message = "编程语言不能为空")
    private String language;

    @NotBlank(message = "代码不能为空")
    private String code;

    @Min(5)
    @Max(300)
    private Integer timeout;
}