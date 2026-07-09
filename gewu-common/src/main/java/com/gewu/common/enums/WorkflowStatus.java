package com.gewu.common.enums;

/**
 * 工作流实例状态枚举.
 */
public enum WorkflowStatus {

    RUNNING("running", "运行中"),
    COMPLETED("completed", "已完成"),
    FAILED("failed", "已失败"),
    SUSPENDED("suspended", "已挂起"),
    TERMINATED("terminated", "已终止");

    private final String code;
    private final String description;

    WorkflowStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static WorkflowStatus fromCode(String code) {
        for (WorkflowStatus status : values()) {
            if (status.code.equals(code)) return status;
        }
        throw new IllegalArgumentException("未知工作流状态: " + code);
    }
}