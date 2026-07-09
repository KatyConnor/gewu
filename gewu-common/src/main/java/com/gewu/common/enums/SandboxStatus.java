package com.gewu.common.enums;

public enum SandboxStatus {

    CREATING("creating", "创建中"),
    RUNNING("running", "运行中"),
    STOPPED("stopped", "已停止"),
    DESTROYED("destroyed", "已销毁"),
    ERROR("error", "异常");

    private final String code;
    private final String description;

    SandboxStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static SandboxStatus fromCode(String code) {
        for (SandboxStatus status : values()) {
            if (status.code.equals(code)) return status;
        }
        throw new IllegalArgumentException("未知沙箱状态: " + code);
    }
}
