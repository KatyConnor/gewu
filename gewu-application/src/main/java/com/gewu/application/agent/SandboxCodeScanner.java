package com.gewu.application.agent;

import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 沙箱代码危险操作扫描器 — 拦截包含危险系统操作的代码.
 *
 * <p>扫描 Python/Shell 代码，拒绝执行包含危险系统调用的代码。
 */
@Component
public class SandboxCodeScanner {

    // Python 危险操作模式
    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            // 系统调用
            Pattern.compile("\\bos\\.system\\s*\\("),
            Pattern.compile("\\bos\\.popen\\s*\\("),
            Pattern.compile("\\bsubprocess\\.(call|run|Popen|check_output|check_call)\\s*\\("),
            
            // 文件系统危险操作
            Pattern.compile("\\bos\\.remove\\s*\\("),
            Pattern.compile("\\bos\\.unlink\\s*\\("),
            Pattern.compile("\\bos\\.rmdir\\s*\\("),
            Pattern.compile("\\bshutil\\.rmtree\\s*\\("),
            
            // 网络操作
            Pattern.compile("\\bsocket\\.(socket|create_connection)\\s*\\("),
            Pattern.compile("\\brequests\\.(get|post|put|delete|head|options|patch)\\s*\\("),
            Pattern.compile("\\burllib\\.request\\.(urlopen|Request)\\s*\\("),
            
            // 进程操作
            Pattern.compile("\\bos\\.fork\\s*\\("),
            Pattern.compile("\\bos\\.exec\\w*\\s*\\("),
            Pattern.compile("\\bos\\.spawn\\w*\\s*\\("),
            
            // 动态代码执行
            Pattern.compile("\\beval\\s*\\("),
            Pattern.compile("\\bexec\\s*\\("),
            Pattern.compile("\\b__import__\\s*\\("),
            
            // 权限提升
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

    /**
     * 扫描代码是否包含危险操作。
     *
     * @param code 待执行的代码
     * @param language 代码语言 (python, shell, etc.)
     * @throws BusinessException 当代码包含危险操作时
     */
    public void scan(String code, String language) {
        if (code == null || code.isBlank()) {
            return;
        }

        String lang = language != null ? language.toLowerCase() : "python";
        
        // 扫描 Python 危险模式
        if ("python".equals(lang) || "py".equals(lang)) {
            scanPython(code);
        }
        
        // 扫描 Shell 危险命令
        if ("shell".equals(lang) || "sh".equals(lang) || "bash".equals(lang)) {
            scanShell(code);
        }
        
        // 通用扫描（所有语言）
        scanCommon(code);
    }

    private void scanPython(String code) {
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(code).find()) {
                throw BusinessException.of(ResultCode.SANDBOX_DANGEROUS_OPERATION,
                        "代码包含危险操作: " + pattern.pattern());
            }
        }
    }

    private void scanShell(String code) {
        String lowerCode = code.toLowerCase();
        for (String cmd : DANGEROUS_SHELL_COMMANDS) {
            if (lowerCode.contains(cmd.toLowerCase())) {
                throw BusinessException.of(ResultCode.SANDBOX_DANGEROUS_OPERATION,
                        "代码包含危险命令: " + cmd);
            }
        }
    }

    private void scanCommon(String code) {
        // 检查是否尝试访问敏感路径
        if (code.contains("/etc/passwd") || code.contains("/etc/shadow") ||
            code.contains("/root") || code.contains("/home")) {
            throw BusinessException.of(ResultCode.SANDBOX_DANGEROUS_OPERATION,
                    "代码尝试访问敏感路径");
        }
        
        // 检查是否尝试读取环境变量中的敏感信息
        if (code.contains("os.environ") && 
            (code.contains("PASSWORD") || code.contains("SECRET") || 
             code.contains("API_KEY") || code.contains("TOKEN"))) {
            throw BusinessException.of(ResultCode.SANDBOX_DANGEROUS_OPERATION,
                    "代码尝试读取敏感环境变量");
        }
    }
}
