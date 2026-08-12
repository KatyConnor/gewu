package com.gewu.application.project.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProjectDTO {

    private String projectId;
    private String projectName;
    private String projectCode;
    private String description;
    private Integer visibility;
    private Integer status;
    private String ownerId;
    private String ownerName;
    private Long memberCount;
    private Long createdAt;
    private String techStack;
    private String worktree;
    private String iconUrl;
    private String iconColor;
    private String currentPhase;
    private Long initiatedAt;
    private Long closedAt;
    /** Git 仓库地址 */
    private String repoUrl;
    /** clone 状态: pending/cloning/ready/failed */
    private String cloneStatus;
    /** 当前 HEAD commit */
    private String headCommit;
    /** 容器内仓库路径 */
    private String repoLocalPath;
}