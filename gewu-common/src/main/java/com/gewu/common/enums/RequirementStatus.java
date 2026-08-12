package com.gewu.common.enums;

import java.util.Arrays;
import java.util.List;

/**
 * 需求状态枚举 — 完整生命周期状态流转.
 */
public enum RequirementStatus {

    // 草稿阶段
    DRAFT("DRAFT", "草稿", true),

    // 需求评审阶段
    PENDING_REVIEW("PENDING_REVIEW", "待需求评审", true),
    IN_REVIEW("IN_REVIEW", "评审中", false),

    // 评审通过后
    APPROVED("APPROVED", "已通过", false),

    // 设计阶段
    DESIGN("DESIGN", "设计", true),
    PENDING_DESIGN_REVIEW("PENDING_DESIGN_REVIEW", "待设计评审", true),
    DESIGN_IN_REVIEW("DESIGN_IN_REVIEW", "设计评审中", false),

    // 计划阶段
    PLANNING("PLANNING", "计划制定", true),
    TASK_ASSIGNED("TASK_ASSIGNED", "任务分配", true),

    // 开发阶段
    PENDING_DEV("PENDING_DEV", "待开发", true),
    IN_DEV("IN_DEV", "开发中", false),
    DEV_COMPLETED("DEV_COMPLETED", "开发完成", false),

    // 测试阶段
    SMOKE_TEST("SMOKE_TEST", "冒烟测试", false),
    SIT_TEST("SIT_TEST", "SIT测试", false),
    UAT_TEST("UAT_TEST", "UAT测试", false),

    // 完成阶段
    RELEASED("RELEASED", "已上线", false),
    ARCHIVED("ARCHIVED", "已归档", false),

    // 分支状态
    PROTOTYPE("PROTOTYPE", "原型", true),
    CANCELLED("CANCELLED", "已取消", true);

    private final String code;
    private final String displayName;
    private final boolean editable;

    RequirementStatus(String code, String displayName, boolean editable) {
        this.code = code;
        this.displayName = displayName;
        this.editable = editable;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
    public boolean isEditable() { return editable; }

    /**
     * 获取所有可编辑的状态（允许修改核心字段）.
     */
    public static List<RequirementStatus> editableStatuses() {
        return Arrays.stream(values()).filter(RequirementStatus::isEditable).toList();
    }

    /**
     * 获取所有终态（不可变更的状态）.
     */
    public static List<RequirementStatus> terminalStatuses() {
        return List.of(ARCHIVED, CANCELLED);
    }
}
