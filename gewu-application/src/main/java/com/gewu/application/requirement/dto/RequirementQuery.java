package com.gewu.application.requirement.dto;

import com.gewu.common.dto.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 需求查询参数.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RequirementQuery extends PageQuery {

    private String keyword;
    private String type;
    private Integer priority;
    private String status;
    private String assigneeId;
    private String projectId;
}
