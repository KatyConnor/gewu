package com.gewu.application.requirement.dto;

import lombok.Data;

/**
 * 提交评审意见命令.
 */
@Data
public class SubmitReviewOpinionCommand {

    private String reviewType;
    private String reviewResult;
    private String reviewComment;
    private String reviewAttachments;
}
