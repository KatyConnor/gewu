package com.gewu.admin.dto.role;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 角色 DTO.
 */
@Data
@Builder
public class RoleDTO {

    private String roleId;
    private String roleName;
    private String roleCode;
    private String description;
    private Integer isSystem;
    private Integer sortOrder;
    /** 数据范围: 1全部 2本部门 3本部门及以下 4本人 */
    private Integer dataScope;
    /** 已分配的权限码列表 */
    private List<String> permissionCodes;
    /** 角色下的用户数 */
    private Integer userCount;
}
