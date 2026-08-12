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
    /** 数据范围: 1全部 2本部门 3本部门及以下 4本人 */
    private Integer dataScope;
}