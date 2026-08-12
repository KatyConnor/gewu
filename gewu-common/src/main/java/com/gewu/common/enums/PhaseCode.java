package com.gewu.common.enums;

import java.util.Arrays;
import java.util.List;

/**
 * 项目生命周期阶段编码.
 */
public enum PhaseCode {

    RESEARCH(1, "项目调研", true),
    PROTOTYPE(2, "原型设计", true),
    INITIATION(3, "立项中", true),
    REQUIREMENT_REVIEW(4, "需求评审", true),
    DESIGN_REVIEW(5, "设计评审", true),
    ESTIMATION(6, "工作量评估", true),
    PLANNING(7, "计划制定", true),
    DEVELOPMENT(8, "开发中", false),
    SELF_TEST(9, "开发自测", false),
    SMOKE_TEST(10, "冒烟测试", false),
    PENDING_SIT(11, "待 SIT 测试", false),
    SIT_TEST(12, "SIT 测试", false),
    PENDING_UAT(13, "待 UAT 测试", false),
    UAT_TEST(14, "UAT 测试", false),
    PENDING_RELEASE(15, "等待上线", false),
    RELEASED(16, "上线完成/部分上线", false),
    CLOSED(17, "已结项", false);

    private final int order;
    private final String displayName;
    private final boolean revertible;

    PhaseCode(int order, String displayName, boolean revertible) {
        this.order = order;
        this.displayName = displayName;
        this.revertible = revertible;
    }

    public int getOrder() { return order; }
    public String getDisplayName() { return displayName; }
    public boolean isRevertible() { return revertible; }

    public static List<PhaseCode> orderedValues() {
        return Arrays.stream(values()).sorted(java.util.Comparator.comparingInt(PhaseCode::getOrder)).toList();
    }

    public static PhaseCode prev(PhaseCode current) {
        List<PhaseCode> ordered = orderedValues();
        int idx = ordered.indexOf(current);
        return idx > 0 ? ordered.get(idx - 1) : null;
    }

    public static PhaseCode next(PhaseCode current) {
        List<PhaseCode> ordered = orderedValues();
        int idx = ordered.indexOf(current);
        return idx < ordered.size() - 1 ? ordered.get(idx + 1) : null;
    }
}
