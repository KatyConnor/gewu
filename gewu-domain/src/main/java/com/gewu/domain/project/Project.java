package com.gewu.domain.project;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project")
public class Project extends BaseEntity {

    private String projectName;
    private String projectCode;
    private String description;
    private Integer visibility;
    private Integer status;
    private String ownerId;
    private String techStack;
    private String worktree;
    private String vcs;
    private String iconUrl;
    private String iconColor;
    private Long timeInitialized;
    private String sandboxes;
    private String commands;
    private String currentPhase;
    private Long initiatedAt;
    private Long closedAt;
    /** Git 仓库地址 */
    private String repoUrl;
    /** 默认分支 */
    private String repoBranch;
    /** clone 状态: pending/cloning/ready/failed */
    private String cloneStatus;
    /** 容器内路径 (如 projects/{projectId}/repo) */
    private String repoLocalPath;
    /** 当前 HEAD commit SHA */
    private String headCommit;
}