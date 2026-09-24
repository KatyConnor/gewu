package com.gewu.admin.dto.user;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 分配角色命令.
 */
@Data
public class AssignRolesCommand {

    @NotNull(message = "角色列表不能为空")
    private List<String> roleCodes;
}
