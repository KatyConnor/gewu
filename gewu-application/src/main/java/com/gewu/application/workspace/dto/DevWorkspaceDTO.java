package com.gewu.application.workspace.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 开发工作空间 DTO.
 */
@Data
@Builder
public class DevWorkspaceDTO {

    private String workspaceId;
    private String userId;
    private String mode;
    private String devSandboxId;
    private String sandboxStatus;
    private String sandboxStatusDesc;
    private String containerId;
    private String mountPath;
}
