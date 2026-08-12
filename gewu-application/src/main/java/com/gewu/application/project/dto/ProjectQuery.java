package com.gewu.application.project.dto;

import com.gewu.common.dto.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 项目查询参数 — 支持搜索条件.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProjectQuery extends PageQuery {

    /** 项目名称（模糊） */
    private String projectName;

    /** 项目编号 */
    private String projectCode;

    /** 项目负责人（用户名） */
    private String ownerName;

    /** 项目状态：1=进行中 2=待立项 3=已上线 */
    private Integer status;
}
