package com.gewu.admin.dto.requirement;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 管理端创建需求命令（需求管理迁移管理端）.
 *
 * <p>需求必须绑定项目（projectId 必填）；assigneeId（分派负责人）可选——
 * 一旦设置，必须是该项目的 project_member 成员（后端强校验），否则拒绝。
 */
@Data
public class AdminRequirementCreateCommand {

    @NotBlank(message = "需求标题不能为空")
    private String title;

    private String description;

    /** STORY/TASK/BUG/IMPROVEMENT/FEATURE/EPIC，默认 STORY */
    private String type;

    /** 0=P0 1=P1 2=P2 3=P3，默认 2 */
    private Integer priority;

    /** 绑定项目 ID（必填） */
    @NotBlank(message = "需求必须绑定项目")
    private String projectId;

    /** 负责人用户 ID（可选；必须为绑定项目的成员） */
    private String assigneeId;

    /** 截止日期（毫秒时间戳） */
    private Long dueDate;

    private Integer storyPoint;

    private Integer estimatedHours;
}
