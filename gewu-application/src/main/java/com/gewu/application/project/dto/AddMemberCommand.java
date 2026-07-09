package com.gewu.application.project.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AddMemberCommand {

    @NotBlank(message = "用户ID不能为空")
    private String userId;

    @NotBlank(message = "角色编码不能为空")
    private String roleCode;
}