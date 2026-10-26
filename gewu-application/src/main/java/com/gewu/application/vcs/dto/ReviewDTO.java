package com.gewu.application.vcs.dto;

import lombok.Data;

/**
 * 代码评审条目（跨平台归一；GitLab 仅 APPROVED 形态）。
 */
@Data
public class ReviewDTO {
    /** APPROVED / CHANGES_REQUESTED / COMMENTED */
    private String state;
    private String reviewer;
    private String body;
    private Long submittedAt;
}
