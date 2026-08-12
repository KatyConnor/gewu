package com.gewu.application.audit.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 待审核项 DTO（聚合各业务审批待办）.
 */
@Data
@Builder
public class PendingAuditDTO {

    /** 审批类型：SKILL_PUBLISH / AGENT_MARKET */
    private String auditType;
    /** 目标 ID */
    private String targetId;
    /** 名称 */
    private String name;
    /** 描述 */
    private String description;
    /** 内容（技能内容/系统提示词） */
    private String content;
    /** 分类 */
    private String category;
    /** 图标 */
    private String emoji;
    /** 创建时间 */
    private Long createdAt;
    /** 创建者 */
    private String createdBy;
}
