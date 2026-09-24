package com.gewu.admin.dto.menu;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新菜单命令（部分更新）.
 */
@Data
public class UpdateMenuCommand {

    private String parentId;

    @Size(max = 64, message = "菜单名称最长 64 字符")
    private String menuName;

    private Integer menuType;

    @Size(max = 128, message = "路由标识最长 128 字符")
    private String path;

    @Size(max = 32, message = "图标名称最长 32 字符")
    private String icon;

    private Integer sortOrder;

    @Size(max = 128, message = "权限码最长 128 字符")
    private String permissionCode;

    private Integer visible;
}
