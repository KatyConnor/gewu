package com.gewu.admin.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 重置密码命令.
 */
@Data
public class ResetPasswordCommand {

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度 8-64 位")
    private String newPassword;
}
