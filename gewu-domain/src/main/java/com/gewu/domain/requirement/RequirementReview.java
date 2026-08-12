package com.gewu.domain.requirement;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 需求评审记录实体.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("requirement_review")
public class RequirementReview extends BaseEntity {

    private String requirementId;
    private String reviewType;
    private String reviewerId;
    private String reviewResult;
    private String reviewComment;
    private String reviewAttachments;
    private Integer reviewOrder;
    private Long completedAt;
}
