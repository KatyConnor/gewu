package com.gewu.domain.user;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseSimpleEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("role_permission")
public class RolePermission extends BaseSimpleEntity {

    private String roleId;
    private String permissionId;
    private String createdBy;
}