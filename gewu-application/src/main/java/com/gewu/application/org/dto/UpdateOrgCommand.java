package com.gewu.application.org.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新机构命令（部分更新）.
 */
@Data
public class UpdateOrgCommand {

    private String parentId;

    @Size(max = 128, message = "机构名称最长 128 字符")
    private String orgName;

    @Size(max = 64, message = "机构编码最长 64 字符")
    private String orgCode;

    private Integer sortOrder;
}
