package com.gewu.sandbox.config;

import com.gewu.sandbox.constant.SandboxConstants;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 沙箱资源配置.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SandboxResourceConfig {

    @Builder.Default
    private Integer cpuLimitMillicores = SandboxConstants.DEFAULT_CPU_LIMIT;

    @Builder.Default
    private Integer memoryLimitMb = SandboxConstants.DEFAULT_MEMORY_LIMIT_MB;

    @Builder.Default
    private Integer diskLimitMb = SandboxConstants.DEFAULT_DISK_LIMIT_MB;

    @Builder.Default
    private Boolean networkEnabled = false;

    @Builder.Default
    private Integer timeoutSeconds = SandboxConstants.DEFAULT_TIMEOUT_SECONDS;

    public boolean isValid() {
        return cpuLimitMillicores > 0
            && cpuLimitMillicores <= SandboxConstants.MAX_CPU_LIMIT
            && memoryLimitMb > 0
            && memoryLimitMb <= SandboxConstants.MAX_MEMORY_LIMIT_MB
            && diskLimitMb > 0
            && diskLimitMb <= SandboxConstants.MAX_DISK_LIMIT_MB
            && timeoutSeconds > 0
            && timeoutSeconds <= SandboxConstants.MAX_TIMEOUT_SECONDS;
    }
}
