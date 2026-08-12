package com.gewu.application.requirement.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 需求评论 DTO.
 */
@Data
@Builder
public class RequirementCommentDTO {

    private String id;
    private String requirementId;
    private String content;
    private String parentId;
    private String attachments;
    private Long createdAt;
    private String createdBy;
    private String createdByName;
}
