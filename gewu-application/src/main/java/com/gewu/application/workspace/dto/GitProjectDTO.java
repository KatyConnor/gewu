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
    private Long lastSyncAt;
    private String headCommit;
    private Long createdAt;
}
