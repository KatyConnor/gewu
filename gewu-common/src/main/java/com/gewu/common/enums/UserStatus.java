package com.gewu.common.enums;

/**
 * 用户状态枚举.
 */
public enum UserStatus {

    ENABLED(1, "启用"),
    DISABLED(2, "禁用"),
    LOCKED(3, "锁定");

    private final int code;
    private final String description;

    UserStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static UserStatus fromCode(int code) {
        for (UserStatus status : values()) {
            if (status.code == code) return status;
        }
        throw new IllegalArgumentException("未知用户状态: " + code);
    }
}