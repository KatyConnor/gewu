package com.gewu.application.org.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建机构命令.
 */
@Data
public class CreateOrgCommand {

    private String parentId;

    @NotBlank(message = "机构名称不能为空")
    @Size(max = 128, message = "机构名称最长 128 字符")
    private String orgName;

    @NotBlank(message = "机构编码不能为空")
    @Size(max = 64, message = "机构编码最长 64 字符")
    private String orgCode;

    private Integer sortOrder;
}
