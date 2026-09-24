package com.gewu.admin.dto.role;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建角色命令.
 */
@Data
public class CreateRoleCommand {

    @NotBlank(message = "角色名称不能为空")
    @Size(max = 64, message = "角色名称最长 64 字符")
    private String roleName;

    @NotBlank(message = "角色编码不能为空")
    @Size(max = 64, message = "角色编码最长 64 字符")
    private String roleCode;

    @Size(max = 256, message = "描述最长 256 字符")
    private String description;

    private Integer sortOrder;
}
