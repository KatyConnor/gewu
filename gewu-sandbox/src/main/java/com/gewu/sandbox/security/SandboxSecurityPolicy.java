package com.gewu.sandbox.security;

import com.gewu.sandbox.constant.SandboxConstants;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 沙箱安全策略.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SandboxSecurityPolicy {

    @Builder.Default
    private String seccompProfile = SandboxConstants.SECCOMP_PROFILE_DEFAULT;

    @Builder.Default
    private String apparmorProfile = SandboxConstants.APPARMOR_PROFILE_DEFAULT;

    @Builder.Default
    private Boolean readOnlyRootFs = true;

    @Builder.Default
    private Boolean dropAllCapabilities = true;

    @Builder.Default
    private Boolean noNewPrivileges = true;

    @Builder.Default
    private Boolean enforceResourceLimits = true;
}
