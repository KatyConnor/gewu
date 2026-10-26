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
    /** 项目简称 */
    private String shortName;
    /** 所属系统 */
    private String systemName;
    /** 团队成员（逗号分隔姓名） */
    private String teamMembers;
    /** 技术负责人 */
    private String techLead;
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
    /** 仓库凭证类型: token/ssh_key/password（项目级凭证，SM4 加密存 repo_credential） */
    private String repoCredentialType;
    /** 仓库用户名（repoCredentialType=password 时配套） */
    private String repoUsername;
    /** 仓库访问凭证（SM4 加密：访问令牌 / SSH 私钥 / 密码），不出 DTO */
    private String repoCredential;
    /** 默认分支 */
    private String repoBranch;
    /** 托管平台类型（github/gitlab/gitee/gitea；NULL=按 repoUrl 域名自动推断，协作适配层用） */
    private String platformType;
    /** 自建实例 REST API 根地址（NULL=按平台默认推导） */
    private String apiBase;
    /** clone 状态: pending/cloning/ready/failed */
    private String cloneStatus;
    /** 最近一次 clone 失败的 stderr 摘要（成功时清空） */
    private String cloneError;
    /** 容器内路径 (如 projects/{projectId}/repo) */
    private String repoLocalPath;
    /** 当前 HEAD commit SHA */
    private String headCommit;
}