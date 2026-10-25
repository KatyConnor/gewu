package com.gewu.admin.dto.requirement;

import lombok.Data;

/**
 * 管理端更新需求命令（全字段可选：null=不修改）.
 *
 * <p>projectId 变更后，若已有/新设 assigneeId 不属于新项目成员将被拒绝；
 * assigneeId 分派校验规则同创建。
 */
@Data
public class AdminRequirementUpdateCommand {

    private String title;

    private String description;

    private String type;

    private Integer priority;

    private String projectId;

    /** null=不变，空串=清空分派，非空=分派（须为绑定项目成员） */
    private String assigneeId;

    private Long dueDate;

    private Integer storyPoint;

    private Integer estimatedHours;

    private String designDoc;

    private String planDoc;

    private String testDoc;
}
