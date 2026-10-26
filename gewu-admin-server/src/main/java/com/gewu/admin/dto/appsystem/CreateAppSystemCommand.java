package com.gewu.admin.dto.appsystem;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建应用系统命令.
 */
@Data
public class CreateAppSystemCommand {

    @NotBlank(message = "系统编码不能为空")
    @Size(max = 64, message = "系统编码最长 64 字符")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "系统编码仅允许字母/数字/下划线/中划线")
    private String systemCode;

    @NotBlank(message = "系统名称不能为空")
    @Size(max = 128, message = "系统名称最长 128 字符")
    private String systemName;

    @Size(max = 64, message = "系统简称最长 64 字符")
    private String shortName;

    @NotBlank(message = "系统分类不能为空")
    @Pattern(regexp = "^(BUSINESS|PLATFORM|MIDDLEWARE|DATA|TOOL)$", message = "系统分类非法")
    private String systemType;

    private String orgId;

    @NotBlank(message = "技术负责人不能为空")
    @Size(max = 64, message = "技术负责人最长 64 字符")
    private String ownerName;

    @Size(max = 128, message = "联系方式最长 128 字符")
    private String ownerContact;

    @Size(max = 256, message = "仓库地址最长 256 字符")
    private String repoUrl;

    @NotNull(message = "重要等级不能为空")
    private Integer criticality;

    private Integer status;

    @Size(max = 512, message = "系统描述最长 512 字符")
    private String description;

    private Integer sortOrder;
}
