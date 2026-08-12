package com.gewu.application.user.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 分配角色权限命令.
 */
@Data
public class AssignPermissionsCommand {

    @NotNull(message = "权限列表不能为空")
    private List<String> permissionCodes;
}
