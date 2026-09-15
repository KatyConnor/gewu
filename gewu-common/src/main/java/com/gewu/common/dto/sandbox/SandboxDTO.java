package com.gewu.common.dto.sandbox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SandboxDTO {

    private String sandboxId;
    private String sandboxName;
    private String image;
    private String status;
    private String statusDesc;
    private Integer cpuCores;
    private Integer memoryMb;
    private Integer diskMb;
    private Boolean networkEnabled;
    private Integer timeoutSeconds;
    private String runtime;
    private Long createdAt;
    private Long startedAt;
    private Long stoppedAt;
    private String ip;
    private String ports;
    private String createdBy;
    private String source;
    private String projectId;
    private String agentId;
    private Integer autoDestroy;
    private Long lastUsedAt;
    private Long expireAt;
    private String workspaceId;
    /** 工作空间挂载路径（workspaceId 非空时为 /workspace） */
    private String mountPath;
}
