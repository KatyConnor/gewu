package com.gewu.application.menu.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建菜单命令.
 */
@Data
public class CreateMenuCommand {

    private String parentId;

    @NotBlank(message = "菜单名称不能为空")
    @Size(max = 64, message = "菜单名称最长 64 字符")
    private String menuName;

    /** 1目录 2菜单 3按钮，默认 2 */
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
