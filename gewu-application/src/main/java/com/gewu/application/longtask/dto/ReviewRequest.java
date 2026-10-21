package com.gewu.application.longtask.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 长任务人工审核请求体（docs/design/45 §7）。 */
@Data
public class ReviewRequest {

    /** approved=达标通过（任务完成）；rejected=驳回（意见注入下一段继续） */
    @NotBlank(message = "审核结论不能为空")
    private String verdict;

    /** 审核意见（驳回时作为整改反馈注入下一段） */
    private String comment;
}
