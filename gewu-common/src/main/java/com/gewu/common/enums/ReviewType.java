package com.gewu.common.enums;

/**
 * 评审类型枚举.
 */
public enum ReviewType {

    REQUIREMENT("REQUIREMENT", "需求评审"),
    DESIGN("DESIGN", "设计评审"),
    TEST("TEST", "测试案例评审");

    private final String code;
    private final String displayName;

    ReviewType(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
}
