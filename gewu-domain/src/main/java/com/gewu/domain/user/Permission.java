package com.gewu.domain.user;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("permission")
public class Permission extends BaseEntity {

    private String permissionCode;
    private String permissionName;
    private String resourceType;
    private String action;
    private String description;
}