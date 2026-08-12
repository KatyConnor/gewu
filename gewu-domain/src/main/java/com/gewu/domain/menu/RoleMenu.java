package com.gewu.domain.menu;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseSimpleEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色菜单关联实体.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("role_menu")
public class RoleMenu extends BaseSimpleEntity {

    private String roleId;
    private String menuId;
    private String createdBy;
}
