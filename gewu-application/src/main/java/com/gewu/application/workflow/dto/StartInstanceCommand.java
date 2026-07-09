package com.gewu.application.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class StartInstanceCommand {

    @NotBlank(message = "实例标题不能为空")
    @Size(max = 128, message = "实例标题最长 128 个字符")
    private String title;

    private String variables;
}
