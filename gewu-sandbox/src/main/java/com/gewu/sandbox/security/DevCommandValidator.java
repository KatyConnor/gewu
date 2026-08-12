package com.gewu.sandbox.security;

import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 开发沙箱命令校验器 - 宽松策略，仅禁止破坏性操作.
 * <p>允许管道、重定向、变量、&& 等，支持 git/maven/npm 等开发命令。
 * 仅用于 source=dev 的沙箱，Agent 沙箱仍使用 {@link CommandValidator}。
 */
@Slf4j
@Component
public class DevCommandValidator {

    private static final Set<String> BLOCKED_PATTERNS = Set.of(
            "rm -rf /", "rm -rf /*", "mkfs", "dd if=/dev/", "shutdown",
            "reboot", "halt", "poweroff", "init 0", "init 6",
            ":(){ :|:& };:"  // fork bomb
    );

    public void validate(String command) {
        if (command == null || command.isBlank()) return;
        String lower = command.toLowerCase();
        for (String pattern : BLOCKED_PATTERNS) {
            if (lower.contains(pattern)) {
                log.warn("开发沙箱命令被拦截: pattern={}, cmd={}", pattern, command);
                throw BusinessException.of(ResultCode.PARAM_INVALID,
                        "开发沙箱禁止执行破坏性命令: " + pattern);
            }
        }
    }
}
