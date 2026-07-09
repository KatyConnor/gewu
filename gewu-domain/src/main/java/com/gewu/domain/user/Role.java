package com.gewu.domain.user;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("role")
public class Role extends BaseEntity {

    private String roleName;
    private String roleCode;
    private String description;
    private Integer isSystem;
    private Integer sortOrder;
}