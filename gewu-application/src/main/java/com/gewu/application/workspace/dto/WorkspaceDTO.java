package com.gewu.application.workspace.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 工作空间 DTO.
 */
@Data
@Builder
public class WorkspaceDTO {

    private String workspaceId;
    private String userId;
    private String workspaceName;
    private Long quotaBytes;
    private Long usedBytes;
    private Integer fileCount;
    /** 使用率百分比 (0-100) */
    private Integer usagePercent;
}
