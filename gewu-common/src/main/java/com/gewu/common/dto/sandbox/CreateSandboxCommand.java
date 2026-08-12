package com.gewu.common.dto.sandbox;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class CreateSandboxCommand {

    private String sandboxName;

    private String image;

    @Min(1)
    @Max(16)
    private Integer cpuCores;

    @Min(128)
    @Max(32768)
    private Integer memoryMb;

    @Min(256)
    @Max(102400)
    private Integer diskMb;

    private Boolean networkEnabled;

    @Min(60)
    @Max(86400)
    private Integer timeout;

    private String env;

    private String cmd;

    private String template;

    private String source;

    private String projectId;

    private String agentId;

    private Integer autoDestroy;

    private Long expireAt;

    /** 关联工作空间ID（非空时沙箱挂载工作空间卷到 /workspace） */
    private String workspaceId;
}