package com.gewu.common.enums;

/**
 * 文档审核状态枚举.
 */
public enum ReviewStatus {

    DRAFT("draft", "草稿"),
    PENDING("pending", "待审核"),
    IN_REVIEW("in_review", "审核中"),
    APPROVED("approved", "已通过"),
    REJECTED("rejected", "已驳回");

    private final String code;
    private final String description;

    ReviewStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() { return code; }
    public String getDescription() { return description; }
}
