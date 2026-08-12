package com.gewu.domain.menu;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 菜单实体 - 树形结构，支持目录/菜单/按钮三种类型.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("menu")
public class Menu extends BaseEntity {

    /** 父菜单ID（NULL 为顶级） */
    private String parentId;
    /** 菜单名称 */
    private String menuName;
    /** 菜单类型：1目录 2菜单 3按钮 */
    private Integer menuType;
    /** 路由标识（对应前端 PageType） */
    private String path;
    /** 图标名称（lucide 图标名） */
    private String icon;
    /** 排序号 */
    private Integer sortOrder;
    /** 所需权限码（NULL 表示无限制） */
    private String permissionCode;
    /** 是否可见：1可见 0隐藏 */
    private Integer visible;
}
