package com.gewu.sandbox.validator;

import com.gewu.sandbox.config.SandboxResourceConfig;
import com.gewu.sandbox.constant.SandboxConstants;
import com.gewu.sandbox.exception.SandboxResourceLimitExceededException;
import org.springframework.stereotype.Component;

@Component
public class SandboxValidator {

    public void validateImage(String image) {
        if (image == null || image.isBlank()) {
            throw new SandboxResourceLimitExceededException("镜像地址不能为空");
        }
        boolean allowed = SandboxConstants.ALLOWED_IMAGE_PREFIXES.stream()
                .anyMatch(image::startsWith);
        if (!allowed) {
            throw new SandboxResourceLimitExceededException("不允许的镜像: " + image);
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
}