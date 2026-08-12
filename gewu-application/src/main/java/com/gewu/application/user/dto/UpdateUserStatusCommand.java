package com.gewu.application.user.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 更新用户状态命令. 1=启用 2=禁用 3=锁定
 */
@Data
public class UpdateUserStatusCommand {

    @NotNull(message = "状态不能为空")
    private Integer status;
}
