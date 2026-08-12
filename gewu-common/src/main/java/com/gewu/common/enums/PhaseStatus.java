package com.gewu.common.enums;

/**
 * 阶段状态枚举.
 */
public enum PhaseStatus {

    NOT_STARTED(0, "未开始"),
    IN_PROGRESS(1, "进行中"),
    COMPLETED(2, "已完成");

    private final int code;
    private final String description;

    PhaseStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() { return code; }
    public String getDescription() { return description; }

    public static PhaseStatus fromCode(int code) {
        for (PhaseStatus s : values()) {
            if (s.code == code) return s;
        }
        return NOT_STARTED;
    }
}
