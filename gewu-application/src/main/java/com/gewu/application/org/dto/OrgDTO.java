package com.gewu.application.org.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 组织机构 DTO - 支持树形结构.
 */
@Data
@Builder
public class OrgDTO {

    private String orgId;
    private String parentId;
    private String orgName;
    private String orgCode;
    private Integer sortOrder;
    /** 该机构下的用户数 */
    private Integer userCount;
    /** 子机构（树形） */
    private List<OrgDTO> children;
}
