package com.gewu.admin.dto.appsystem;

import lombok.Builder;
import lombok.Data;

/**
 * 应用系统 DTO.
 */
@Data
@Builder
public class AppSystemDTO {

    private String systemId;
    private String systemCode;
    private String systemName;
    private String shortName;
    /** 系统分类：BUSINESS/PLATFORM/MIDDLEWARE/DATA/TOOL */
    private String systemType;
    private String orgId;
    /** 所属机构名称（展示用） */
    private String orgName;
    private String ownerName;
    private String ownerContact;
    private String repoUrl;
    /** 重要等级：1核心 2重要 3一般 */
    private Integer criticality;
    /** 运行状态：1启用 2停用 */
    private Integer status;
    private String description;
    private Integer sortOrder;
    /** 子模块服务数 */
    private Integer serviceCount;
    private Long createdAt;
    private Long updatedAt;
}
