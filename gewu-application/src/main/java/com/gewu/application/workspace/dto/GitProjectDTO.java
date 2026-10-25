package com.gewu.application.workspace.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Git 项目 DTO.
 */
@Data
@Builder
public class GitProjectDTO {

    private String projectId;
    private String workspaceId;
    private String projectName;
    private String repoUrl;
    private String repoBranch;
    private String localPath;
    private String cloneStatus;
    /** 最近一次 clone 失败的 stderr 摘要（ready 时为空） */
    private String cloneError;
    private Long lastSyncAt;
    private String headCommit;
    private Long createdAt;
    /** 项目级凭证类型: token/ssh_key/password（来自项目配置，密文不出后端） */
    private String repoCredentialType;
    /** 项目级仓库用户名（password 型配套展示） */
    private String repoUsername;
    /** 项目是否已配置仓库凭证 */
    private Boolean hasRepoCredential;
}
