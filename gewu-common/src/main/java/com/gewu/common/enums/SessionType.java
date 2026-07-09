package com.gewu.common.enums;

/**
 * 会话类型枚举.
 */
public enum SessionType {

    GROUP(1, "群聊"),
    PRIVATE(2, "私聊"),
    AI_ASSIST(3, "AI辅助");

    private final int code;
    private final String description;

    SessionType(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static SessionType fromCode(int code) {
        for (SessionType type : values()) {
            if (type.code == code) return type;
        }
        throw new IllegalArgumentException("未知会话类型: " + code);
    }
}