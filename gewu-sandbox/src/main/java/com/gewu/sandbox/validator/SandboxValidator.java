package com.gewu.sandbox.validator;

import com.gewu.sandbox.config.SandboxResourceConfig;
import com.gewu.sandbox.constant.SandboxConstants;
import com.gewu.sandbox.exception.SandboxResourceLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 沙箱准入校验器——镜像白名单与资源上限的服务端强制点。
 * <p>由 SandboxService.createSandbox 在容器创建前调用；CPU/内存/磁盘/超时上限
 * 以 SandboxConstants 为准（严于 CreateSandboxCommand 的 DTO 校验）。
 */
@Component
public class SandboxValidator {

    /** 镜像白名单覆盖配置（逗号分隔前缀；空=使用 SandboxConstants 默认白名单） */
    @Value("${gewu.sandbox.security.allowed-image-prefixes:}")
    private String allowedImagePrefixesConfig;

    public void validateImage(String image) {
        if (image == null || image.isBlank()) {
            throw new SandboxResourceLimitExceededException("镜像地址不能为空");
        }
        boolean allowed = resolvePrefixes().stream().anyMatch(image::startsWith);
        if (!allowed) {
            throw new SandboxResourceLimitExceededException("不允许的镜像: " + image);
        }
    }

    /** 资源上限强制（任一超限即拒绝）。注意 MAX_CPU_LIMIT 单位为毫核，入参为核数 */
    public void validateResourceLimits(Integer cpuCores, Integer memoryMb, Integer diskMb, Integer timeout) {
        if (cpuCores != null && cpuCores * 1000L > SandboxConstants.MAX_CPU_LIMIT) {
            throw new SandboxResourceLimitExceededException(
                    "CPU 超出上限: " + (SandboxConstants.MAX_CPU_LIMIT / 1000) + " 核");
        }
        if (memoryMb != null && memoryMb > SandboxConstants.MAX_MEMORY_LIMIT_MB) {
            throw new SandboxResourceLimitExceededException(
                    "内存超出上限: " + SandboxConstants.MAX_MEMORY_LIMIT_MB + " MB");
        }
        if (diskMb != null && diskMb > SandboxConstants.MAX_DISK_LIMIT_MB) {
            throw new SandboxResourceLimitExceededException(
                    "磁盘超出上限: " + SandboxConstants.MAX_DISK_LIMIT_MB + " MB");
        }
        if (timeout != null && timeout > SandboxConstants.MAX_TIMEOUT_SECONDS) {
            throw new SandboxResourceLimitExceededException(
                    "超时时间超出上限: " + SandboxConstants.MAX_TIMEOUT_SECONDS + " 秒");
        }
    }

    public void validateResourceConfig(SandboxResourceConfig config) {
        if (!config.isValid()) {
            throw new SandboxResourceLimitExceededException("资源配置超出限制");
        }
    }

    public void validateCommand(String command) {
        for (String blocked : SandboxConstants.BLOCKED_COMMANDS) {
            if (command.contains(blocked)) {
                throw new SandboxResourceLimitExceededException("禁止执行的命令: " + blocked);
            }
        }
    }

    private List<String> resolvePrefixes() {
        if (allowedImagePrefixesConfig == null || allowedImagePrefixesConfig.isBlank()) {
            return SandboxConstants.ALLOWED_IMAGE_PREFIXES;
        }
        return Arrays.stream(allowedImagePrefixesConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
