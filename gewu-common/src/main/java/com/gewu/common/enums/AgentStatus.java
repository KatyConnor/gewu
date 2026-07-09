package com.gewu.common.enums;

/**
 * Agent 状态枚举.
 */
public enum AgentStatus {

    ENABLED(1, "启用"),
    DISABLED(2, "禁用");

    private final int code;
    private final String description;

    AgentStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static AgentStatus fromCode(int code) {
        for (AgentStatus status : values()) {
            if (status.code == code) return status;
        }
        throw new IllegalArgumentException("未知 Agent 状态: " + code);
    }
}