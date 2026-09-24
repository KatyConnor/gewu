package com.gewu.admin.dto.role;

import lombok.Builder;
import lombok.Data;

/**
 * 权限 DTO.
 */
@Data
@Builder
public class PermissionDTO {

    private String permissionId;
    private String permissionCode;
    private String permissionName;
    private String resourceType;
    private String action;
    private String description;
}
