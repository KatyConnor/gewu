package com.gewu.desktop.security;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 桌面命令校验器（D1-16）：shell.exec 工具的黑名单防线（移植平台沙箱 CommandValidator 精神）。
 * 命中黑名单一律拒绝（ToolResult.failure 呈现给模型）；其余命令受三级权限模式约束
 * （READ_ONLY 全拒 / WORKSPACE_WRITE 自动授权+审计 / FULL_AUTO 自动授权）。
 *
 * @since 1.0.0
 */
public final class DesktopCommandValidator {

    private DesktopCommandValidator() {
    }

    /** 破坏性命令黑名单（16 类，对齐平台沙箱 CommandValidator 规则集） */
    private static final List<Pattern> DENY_PATTERNS = List.of(
            Pattern.compile("rm\\s+(-[a-zA-Z]*[rf][a-zA-Z]*\\s+)*(/|~|\\$HOME)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("rm\\s+-[a-zA-Z]*r[a-zA-Z]*f|rm\\s+-[a-zA-Z]*f[a-zA-Z]*r", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmkfs(\\.\\w+)?\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdd\\b[^|]*of=/dev/(sd|nvme|hd|vd)", Pattern.CASE_INSENSITIVE),
            Pattern.compile(":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\};:"), // fork 炸弹
            Pattern.compile("\\b(shutdown|reboot|halt|poweroff)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\binit\\s+[06]\\b"),
            Pattern.compile("chmod\\s+-R\\s+777\\s+/(\\s|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("chown\\s+-R\\s+[^ ]+\\s+/(\\s|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile(">\\s*/dev/(sd|nvme|hd|vd)"),
            Pattern.compile("\\b(format\\s+[c-z]:|del\\s+/[fsq].*\\s+c:\\\\|rd\\s+/s\\s+/q\\s+c:\\\\)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcurl\\b[^|]*\\|\\s*(ba)?sh\\b"), // 管道执行远程脚本
            Pattern.compile("\\bwget\\b[^|]*\\|\\s*(ba)?sh\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmv\\b[^;|&]*\\s+/dev/null\\s*$"),
            Pattern.compile("\\bhistory\\s+-c\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bkill\\s+-9\\s+-1\\b|\\bpkill\\s+-9\\s+\\.", Pattern.CASE_INSENSITIVE)
    );

    /** 校验；命中黑名单返回拒绝原因，通过返回 null */
    public static String validate(String command) {
        if (command == null || command.isBlank()) {
            return "命令为空";
        }
        for (Pattern p : DENY_PATTERNS) {
            if (p.matcher(command).find()) {
                return "命令命中危险操作黑名单（" + p.pattern() + "），已被拒绝";
            }
        }
        return null;
    }
}
