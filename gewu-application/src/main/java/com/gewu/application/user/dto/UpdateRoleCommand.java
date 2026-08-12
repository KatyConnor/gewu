package com.gewu.application.user.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新角色命令（roleCode 不可改）.
 */
@Data
public class UpdateRoleCommand {

    @Size(max = 64, message = "角色名称最长 64 字符")
    private String roleName;

    @Size(max = 256, message = "描述最长 256 字符")
    private String description;

    private Integer sortOrder;

    /** 数据范围: 1全部 2本部门 3本部门及以下 4本人 */
    private Integer dataScope;
}
