package com.gewu.admin.dto.project;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 管理端创建项目命令（项目管理迁移管理端：后台为项目唯一创建入口）.
 *
 * <p>团队分发：techLeadUserId 落 project_member(TECH_LEAD) 并作为 ownerId；
 * memberUserIds 落 project_member(MEMBER)——用户侧"我的项目"按成员行过滤。
 * tech_lead/team_members 姓名列由所选用户回填，仅作展示冗余。
 */
@Data
public class AdminProjectCreateCommand {

    @NotBlank(message = "项目名称不能为空")
    private String projectName;

    private String shortName;

    /** 所属系统（管理端"应用系统"台账） */
    private String systemName;

    private String description;

    /** 0=私有 1=团队可见 2=公开，默认 0 */
    private Integer visibility;

    private String techStack;

    private String vcs;

    private String worktree;

    private String iconUrl;

    private String iconColor;

    private String repoUrl;
    /** 托管平台类型（github/gitlab/gitee/gitea；空=按域名推断） */
    private String platformType;
    /** 自建实例 REST API 根地址（空=按平台默认推导） */
    private String apiBase;

    /** 仓库凭证类型: token/ssh_key/password */
    private String repoCredentialType;

    private String repoUsername;

    /** 仓库访问凭证明文（SM4 加密落库，不回传） */
    private String repoCredential;

    /** 技术负责人用户 ID（可选；指定后作为项目所有者） */
    private String techLeadUserId;

    /** 团队成员用户 ID 列表（可选；自动剔除与负责人重复项） */
    private List<String> memberUserIds;
}
