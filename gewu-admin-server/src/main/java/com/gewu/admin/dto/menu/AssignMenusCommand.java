package com.gewu.admin.dto.menu;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 分配角色菜单命令（替换 role_menu）.
 */
@Data
public class AssignMenusCommand {

    @NotNull(message = "菜单ID列表不能为空")
    private List<String> menuIds;
}
