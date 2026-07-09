package com.gewu.common.enums;

/**
 * 消息类型枚举.
 */
public enum MessageType {

    TEXT("text", "文本"),
    CODE("code", "代码"),
    IMAGE("image", "图片"),
    FILE("file", "文件"),
    SYSTEM("system", "系统"),
    AI("ai", "AI回复");

    private final String code;
    private final String description;

    MessageType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static MessageType fromCode(String code) {
        for (MessageType type : values()) {
            if (type.code.equals(code)) return type;
        }
        throw new IllegalArgumentException("未知消息类型: " + code);
    }
}