package com.gewu.sandbox.security;

import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 沙箱命令安全校验器 — 防止 shell 注入攻击.
 *
 * <p>拒绝包含危险字符的命令，如分号、管道、命令替换等。
 */
@Component
public class CommandValidator {

    // 危险字符和模式：; | & $ ` ( ) { } < > \n \r
    private static final Pattern DANGEROUS_PATTERN = Pattern.compile(
            "[;|&$`\\(\\)\\{\\}<>\\\\]"
    );

    // 危险的 shell 操作符
    private static final Pattern DANGEROUS_OPERATOR = Pattern.compile(
            "\\|\\||&&|\\|>|\\<\\<|\\>\\>|\\$\\(|\\$\\{|`"
    );

    // 危险命令
    private static final String[] DANGEROUS_COMMANDS = {
            "rm", "mkfs", "dd", "shutdown", "reboot", "halt", "poweroff",
            "init", "kill", "pkill", "killall", "su", "sudo", "xargs"
    };

    /**
     * 校验命令是否安全。
     *
     * @param command 待执行的命令
     * @throws BusinessException 当命令包含危险字符或模式时
     */
    public void validate(String command) {
        if (command == null || command.isBlank()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "命令不能为空");
        }

        // 检查危险字符
        if (DANGEROUS_PATTERN.matcher(command).find()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, 
                    "命令包含危险字符，禁止使用 ; | & $ ` ( ) { } < > \\");
        }

        // 检查危险操作符
        if (DANGEROUS_OPERATOR.matcher(command).find()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, 
                    "命令包含危险操作符，禁止使用 || && | > << >> $( ${ `");
        }

        // 检查危险命令
        String trimmed = command.trim().toLowerCase();
        for (String dangerous : DANGEROUS_COMMANDS) {
            if (trimmed.startsWith(dangerous + " ") || trimmed.equals(dangerous)) {
                throw BusinessException.of(ResultCode.PARAM_INVALID, 
                        "禁止执行危险命令: " + dangerous);
            }
        }

        // 检查换行符
        if (command.contains("\n") || command.contains("\r")) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, 
                    "命令不能包含换行符");
        }
    }
}
