package com.gewu.admin.dto.role;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建权限命令.
 */
@Data
public class CreatePermissionCommand {

    @NotBlank(message = "权限编码不能为空")
    @Size(max = 128, message = "权限编码最长 128 字符")
    private String permissionCode;

    @NotBlank(message = "权限名称不能为空")
    @Size(max = 128, message = "权限名称最长 128 字符")
    private String permissionName;

    @Size(max = 64)
    private String resourceType;

    @Size(max = 64)
    private String action;

    @Size(max = 256)
    private String description;
}
