package com.gewu.common.enums;

/**
 * 会话输入交付方式枚举.
 */
public enum DeliveryMode {

    STEER("steer", "引导"),
    QUEUE("queue", "排队"),
    RESUME("resume", "恢复");

    private final String code;
    private final String description;

    DeliveryMode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static DeliveryMode fromCode(String code) {
        for (DeliveryMode mode : values()) {
            if (mode.code.equals(code)) return mode;
        }
        throw new IllegalArgumentException("未知交付方式: " + code);
    }
}