package com.gewu.admin.dto.project;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 管理端项目视图（不含仓库凭证等敏感字段）.
 */
@Data
@Builder
public class AdminProjectDTO {

    private String projectId;

    private String projectName;

    private String projectCode;

    private String shortName;

    private String systemName;

    /** 技术负责人（姓名展示冗余） */
    private String techLead;

    /** 团队成员（逗号分隔姓名展示冗余） */
    private String teamMembers;

    /** 技术负责人用户 ID（编辑回显选择器） */
    private String techLeadUserId;

    /** 团队成员用户 ID 列表（编辑回显选择器） */
    private List<String> memberUserIds;

    private String description;

    private Integer visibility;

    private Integer status;

    private String ownerId;

    private String ownerName;

    private Long memberCount;

    private Long createdAt;

    private String techStack;

    private String worktree;

    private String vcs;

    private String iconUrl;

    private String iconColor;

    private String repoUrl;
    /** 托管平台类型（github/gitlab/gitee/gitea；空=按域名推断） */
    private String platformType;
    /** 自建实例 REST API 根地址（空=按平台默认推导） */
    private String apiBase;

    private String repoBranch;

    private String cloneStatus;

    private String headCommit;

    private String repoLocalPath;

    private String currentPhase;

    private Long initiatedAt;

    private Long closedAt;
}
