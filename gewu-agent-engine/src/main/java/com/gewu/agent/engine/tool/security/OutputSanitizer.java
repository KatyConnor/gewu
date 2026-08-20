package com.gewu.agent.engine.tool.security;

import com.gewu.agent.engine.spi.ToolConfig;
import com.gewu.agent.engine.tool.ToolContext;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

/**
 * 输出安全检查器 - 安全纵深五层之输出安全层。
 * <p>检测并脱敏 LLM/工具输出中的 PII（个人身份信息）：
 * <ul>
 *   <li>手机号 -> 1**********（保留首尾位）</li>
 *   <li>邮箱 -> a***@example.com</li>
 *   <li>身份证号 -> 110***********1234</li>
 *   <li>银行卡号 -> 6222****1234</li>
 * </ul>
 * 实现 {@link SecurityCheck} 接口，作为 SecurityChain 插件。
 *
 * @since 1.0.0
 */
@Slf4j
public class OutputSanitizer implements SecurityCheck {

    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b([\\w.+-]+)@([\\w-]+\\.[\\w.-]+)\\b");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile(
            "(?<!\\d)\\d{18}(?!\\d)|(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern BANK_CARD_PATTERN = Pattern.compile(
            "(?<!\\d)\\d{16,19}(?!\\d)");

    private static final int MAX_PHONE_DISPLAY = 3;
    private static final int MAX_CARD_DISPLAY = 4;

    @Override
    public void check(String toolName, String arguments, ToolContext context, ToolConfig config) {
        // 输入检查由 PromptInjectionDetector 负责，此 SecurityCheck 实现仅做 passthrough
    }

    /**
     * 检查并脱敏输出（非 SecurityCheck 接口方法，供 ToolExecutor 调用）。
     */
    public String checkOutput(String output) {
        if (output == null || output.isBlank()) return output;

        String sanitized = output;

        // 脱敏手机号
        sanitized = PHONE_PATTERN.matcher(sanitized).replaceAll(match -> {
            String phone = match.group();
            return phone.substring(0, MAX_PHONE_DISPLAY) + "********" + phone.substring(phone.length() - 2);
        });

        // 脱敏邮箱
        sanitized = EMAIL_PATTERN.matcher(sanitized).replaceAll(match -> {
            String user = match.group(1);
            String domain = match.group(2);
            String maskedUser = user.length() > 1 ? user.charAt(0) + "***" : "***";
            return maskedUser + "@" + domain;
        });

        // 脱敏身份证号
        sanitized = ID_CARD_PATTERN.matcher(sanitized).replaceAll(match -> {
            String id = match.group();
            return id.substring(0, 3) + "***********" + id.substring(id.length() - 4);
        });

        // 脱敏银行卡号
        sanitized = BANK_CARD_PATTERN.matcher(sanitized).replaceAll(match -> {
            String card = match.group();
            if (card.length() >= 16) {
                return card.substring(0, 4) + "****" + card.substring(card.length() - 4);
            }
            return card;
        });

        if (!sanitized.equals(output)) {
            log.debug("OutputSanitizer: 检测并脱敏 PII, originalLen={}, sanitizedLen={}",
                    output.length(), sanitized.length());
        }

        return sanitized;
    }
}