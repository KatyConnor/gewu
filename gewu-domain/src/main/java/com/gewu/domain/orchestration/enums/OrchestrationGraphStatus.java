package com.gewu.domain.orchestration.enums;

/**
 * 编排图状态（orchestration_graph.status，AE-P2-2 enum 化）。
 * <p>DB 为小写 code（draft/active），本枚举带 code 映射；状态机：
 * DRAFT → ACTIVE（激活，含版本快照）→ DRAFT（下架）；仅 DRAFT 可编辑/回滚版本。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-13
 */
public enum OrchestrationGraphStatus {

    /** 草稿（可编辑/可回滚版本） */
    DRAFT("draft"),

    /** 已激活（可执行，含不可变定义快照） */
    ACTIVE("active");

    private final String code;

    OrchestrationGraphStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
