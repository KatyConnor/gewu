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
}
