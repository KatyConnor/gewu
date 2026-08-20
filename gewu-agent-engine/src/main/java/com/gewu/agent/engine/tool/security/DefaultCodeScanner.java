package com.gewu.agent.engine.tool.security;

import com.gewu.agent.engine.AgentEngineException;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Pattern;

/**
 * {@link CodeScanner} 默认实现 - 扫描沙箱代码中的危险操作。
 * <p>拦截 Python 危险系统调用 / 文件删除 / 网络操作 / 动态执行 / 权限提升，
 * 以及 Shell 危险命令与敏感路径访问。
 *
 * @since 1.0.0
 */
@Slf4j
public class DefaultCodeScanner implements CodeScanner {

    // Python 危险操作模式
    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            Pattern.compile("\\bos\\.system\\s*\\("),
            Pattern.compile("\\bos\\.popen\\s*\\("),
            Pattern.compile("\\bsubprocess\\.(call|run|Popen|check_output|check_call)\\s*\\("),
            Pattern.compile("\\bos\\.remove\\s*\\("),
            Pattern.compile("\\bos\\.unlink\\s*\\("),
            Pattern.compile("\\bos\\.rmdir\\s*\\("),
            Pattern.compile("\\bshutil\\.rmtree\\s*\\("),
            Pattern.compile("\\bsocket\\.(socket|create_connection)\\s*\\("),
            Pattern.compile("\\brequests\\.(get|post|put|delete|head|options|patch)\\s*\\("),
            Pattern.compile("\\burllib\\.request\\.(urlopen|Request)\\s*\\("),
            Pattern.compile("\\bos\\.fork\\s*\\("),
            Pattern.compile("\\bos\\.exec\\w*\\s*\\("),
            Pattern.compile("\\bos\\.spawn\\w*\\s*\\("),
            Pattern.compile("\\beval\\s*\\("),
            Pattern.compile("\\bexec\\s*\\("),
            Pattern.compile("\\b__import__\\s*\\("),
            Pattern.compile("\\bos\\.setuid\\s*\\("),
            Pattern.compile("\\bos\\.setgid\\s*\\(")
    );

    // Shell 危险命令
    private static final List<String> DANGEROUS_SHELL_COMMANDS = List.of(
            "rm -rf", "rm -r", "mkfs", "dd if=",
            "shutdown", "reboot", "halt", "poweroff",
            "mount", "umount", "fdisk", "parted",
            "chmod 777", "chown",
            "wget ", "curl ",
            "nc -l", "ncat -l",
            "iptables", "ip link"
    );

    @Override
    public void scan(String code, String language) {
        if (code == null || code.isBlank()) {
            return;
        }
        String lang = language != null ? language.toLowerCase() : "python";

        if ("python".equals(lang) || "py".equals(lang)) {
            scanPython(code);
        }
        if ("shell".equals(lang) || "sh".equals(lang) || "bash".equals(lang)) {
            scanShell(code);
        }
        scanCommon(code);
    }

    private void scanPython(String code) {
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(code).find()) {
                throw AgentEngineException.of("SANDBOX_DANGEROUS_OPERATION",
                        "代码包含危险操作: " + pattern.pattern());
            }
        }
    }

    private void scanShell(String code) {
        String lowerCode = code.toLowerCase();
        for (String cmd : DANGEROUS_SHELL_COMMANDS) {
            if (lowerCode.contains(cmd.toLowerCase())) {
                throw AgentEngineException.of("SANDBOX_DANGEROUS_OPERATION",
                        "代码包含危险命令: " + cmd);
            }
        }
    }

    private void scanCommon(String code) {
        if (code.contains("/etc/passwd") || code.contains("/etc/shadow") ||
                code.contains("/root") || code.contains("/home")) {
            throw AgentEngineException.of("SANDBOX_DANGEROUS_OPERATION", "代码尝试访问敏感路径");
        }
        if (code.contains("os.environ") &&
                (code.contains("PASSWORD") || code.contains("SECRET") ||
                        code.contains("API_KEY") || code.contains("TOKEN"))) {
            throw AgentEngineException.of("SANDBOX_DANGEROUS_OPERATION", "代码尝试读取敏感环境变量");
        }
    }
}
