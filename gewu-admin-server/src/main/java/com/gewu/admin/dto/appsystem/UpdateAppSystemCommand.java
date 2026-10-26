package com.gewu.admin.dto.appsystem;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新应用系统命令（编码不可变，仅允许更新其余字段）.
 */
@Data
public class UpdateAppSystemCommand {

    @Size(max = 128, message = "系统名称最长 128 字符")
    private String systemName;

    @Size(max = 64, message = "系统简称最长 64 字符")
    private String shortName;

    @Pattern(regexp = "^(BUSINESS|PLATFORM|MIDDLEWARE|DATA|TOOL)$", message = "系统分类非法")
    private String systemType;

    private String orgId;

    @Size(max = 64, message = "技术负责人最长 64 字符")
    private String ownerName;

    @Size(max = 128, message = "联系方式最长 128 字符")
    private String ownerContact;

    @Size(max = 256, message = "仓库地址最长 256 字符")
    private String repoUrl;

    private Integer criticality;

    private Integer status;

    @Size(max = 512, message = "系统描述最长 512 字符")
    private String description;

    private Integer sortOrder;
}
