package com.gewu.common.util;

/**
 * 日志脱敏工具类 — 对敏感信息进行掩码处理，防止日志泄露用户隐私。
 * <p>
 * 提供以下脱敏能力：
 * <ul>
 *   <li>用户名脱敏：保留首尾字符，中间替换为 ***</li>
 *   <li>邮箱脱敏：保留首尾字符和域名，中间替换为 ***</li>
 *   <li>文本截断：超长文本截断并标记</li>
 * </ul>
 *
 * @since 1.0.0
 */
public class LogMasking {

    /**
     * 对用户名进行脱敏处理。
     * <p>
     * 规则：长度 &le;1 返回 "***"；长度 &le;3 保留首字符；其他保留首尾字符。
     *
     * @param username 原始用户名
     * @return 脱敏后的用户名
     * @since 1.0.0
     */
    public static String maskUsername(String username) {
        if (username == null || username.length() <= 1) {
            return "***";
        }
        if (username.length() <= 3) {
            return username.charAt(0) + "***";
        }
        return username.charAt(0) + "***" + username.charAt(username.length() - 1);
    }

    /**
     * 对邮箱地址进行脱敏处理。
     * <p>
     * 规则：保留 @ 后域名部分，@ 前本地部分保留首尾字符。
     *
     * @param email 原始邮箱地址
     * @return 脱敏后的邮箱地址，格式非法时返回 "***"
     * @since 1.0.0
     */
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        String[] parts = email.split("@");
        String local = parts[0];
        String domain = parts[1];
        if (local.length() <= 2) {
            return "***@" + domain;
        }
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + "@" + domain;
    }

    /**
     * 截断超长文本，防止日志膨胀。
     *
     * @param input  原始文本
     * @param maxLen 最大允许长度
     * @return 截断后的文本（超出部分替换为 "...[truncated]"），null 输入返回 null
     * @since 1.0.0
     */
    public static String truncate(String input, int maxLen) {
        if (input == null) return null;
        if (input.length() <= maxLen) return input;
        return input.substring(0, maxLen) + "...[truncated]";
    }
}
