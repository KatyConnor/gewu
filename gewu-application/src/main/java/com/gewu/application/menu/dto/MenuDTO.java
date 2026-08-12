package com.gewu.application.menu.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 菜单 DTO - 支持树形结构.
 */
@Data
@Builder
public class MenuDTO {

    private String menuId;
    private String parentId;
    private String menuName;
    /** 菜单类型：1目录 2菜单 3按钮 */
    private Integer menuType;
    /** 路由标识（对应前端 PageType） */
    private String path;
    /** 图标名称 */
    private String icon;
    private Integer sortOrder;
    /** 所需权限码 */
    private String permissionCode;
    /** 是否可见：1可见 0隐藏 */
    private Integer visible;
    /** 子菜单（树形） */
    private List<MenuDTO> children;
}
