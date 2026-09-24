package com.gewu.admin.dto.role;

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
