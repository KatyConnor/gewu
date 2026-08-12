package com.gewu.application.agent;

import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MCP 命令安全校验器 — 防止命令注入攻击.
 *
 * <p>校验 MCP Server 的 command 和 args，拒绝危险命令和参数。
 */
@Component
public class McpCommandValidator {

    // 允许的白名单命令（常见安全的 MCP 服务器命令）
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "node", "npm", "npx", "python", "python3", "pip", "pip3",
            "java", "javac", "mvn", "gradle",
            "go", "rustc", "cargo",
            "ruby", "gem",
            "php",
            "docker", "kubectl"
    );

    // 危险字符和模式
    private static final Pattern DANGEROUS_PATTERN = Pattern.compile(
            "[;|&$`\\(\\)\\{\\}<>\\\\]"
    );

    // 危险操作符
    private static final Pattern DANGEROUS_OPERATOR = Pattern.compile(
            "\\|\\||&&|\\|>|\\<\\<|\\>\\>|\\$\\(|\\$\\{|`"
    );

    // 危险命令
    private static final List<String> DANGEROUS_COMMANDS = List.of(
            "rm", "mkfs", "dd", "shutdown", "reboot", "halt", "poweroff",
            "init", "kill", "pkill", "killall", "su", "sudo", "mount", "umount",
            "fdisk", "parted", "wipefs"
    );

    /**
     * 校验 MCP 命令和参数是否安全。
     *
     * @param command 命令
     * @param args    参数列表
     * @throws BusinessException 当命令或参数包含危险内容时
     */
    public void validate(String command, List<String> args) {
        if (command == null || command.isBlank()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "MCP 命令不能为空");
        }

        // 提取命令名（去掉路径）
        String commandName = command.trim();
        int lastSlash = commandName.lastIndexOf('/');
        if (lastSlash >= 0) {
            commandName = commandName.substring(lastSlash + 1);
        }

        // 检查是否在白名单中
        if (!ALLOWED_COMMANDS.contains(commandName)) {
            throw BusinessException.of(ResultCode.PARAM_INVALID,
                    "MCP 命令不在允许列表中: " + commandName + "。允许的命令: " + String.join(", ", ALLOWED_COMMANDS));
        }

        // 检查命令本身是否包含危险字符
        validateNoDangerousChars(command, "命令");

        // 检查参数
        if (args != null) {
            for (String arg : args) {
                if (arg != null && !arg.isBlank()) {
                    validateNoDangerousChars(arg, "参数");
                }
            }
        }
    }

    private void validateNoDangerousChars(String value, String fieldName) {
        // 检查危险字符
        if (DANGEROUS_PATTERN.matcher(value).find()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID,
                    "MCP " + fieldName + "包含危险字符，禁止使用 ; | & $ ` ( ) { } < > \\");
        }

        // 检查危险操作符
        if (DANGEROUS_OPERATOR.matcher(value).find()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID,
                    "MCP " + fieldName + "包含危险操作符，禁止使用 || && | > << >> $( ${ `");
        }

        // 检查危险命令
        String trimmed = value.trim().toLowerCase();
        for (String dangerous : DANGEROUS_COMMANDS) {
            if (trimmed.startsWith(dangerous + " ") || trimmed.equals(dangerous)) {
                throw BusinessException.of(ResultCode.PARAM_INVALID,
                        "MCP 禁止使用危险命令: " + dangerous);
            }
        }

        // 检查换行符
        if (value.contains("\n") || value.contains("\r")) {
            throw BusinessException.of(ResultCode.PARAM_INVALID,
                    "MCP " + fieldName + "不能包含换行符");
        }
    }
}
