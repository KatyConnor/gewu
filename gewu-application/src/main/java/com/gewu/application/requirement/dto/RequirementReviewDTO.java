package com.gewu.application.requirement.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 评审记录 DTO.
 */
@Data
@Builder
public class RequirementReviewDTO {

    private String id;
    private String requirementId;
    private String reviewType;
    private String reviewTypeDesc;
    private String reviewerId;
    private String reviewerName;
    private String reviewResult;
    private String reviewResultDesc;
    private String reviewComment;
    private String reviewAttachments;
    private Integer reviewOrder;
    private Long completedAt;
    private Long createdAt;
    private String createdBy;
}
