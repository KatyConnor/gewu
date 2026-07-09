package com.gewu.common.enums;

/**
 * 会话状态枚举.
 */
public enum SessionStatus {

    ACTIVE(1, "活跃"),
    ARCHIVED(2, "归档"),
    CLOSED(3, "关闭");

    private final int code;
    private final String description;

    SessionStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static SessionStatus fromCode(int code) {
        for (SessionStatus status : values()) {
            if (status.code == code) return status;
        }
        throw new IllegalArgumentException("未知会话状态: " + code);
    }
}