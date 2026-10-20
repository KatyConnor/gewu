package com.gewu.common.security;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 密钥泄漏扫描 — 特征白名单匹配（设计文档 56 §8.5）.
 *
 * <p>用于入口调度文件等治理文本的保存/初始化入口：命中即拒绝落库，
 * 防止访问令牌/私钥等凭据经配置文本进入 MinIO/Git。保守取低误报特征集，
 * 不做通用熵检测；命中返回类型描述，由调用方组织拒绝消息。
 */
public final class SecretScanUtil {

    private record SecretPattern(String type, Pattern pattern) {}

    private static final List<SecretPattern> PATTERNS = List.of(
            new SecretPattern("OpenAI 风格访问令牌（sk-…）",
                    Pattern.compile("sk-[A-Za-z0-9]{20,}")),
            new SecretPattern("AWS 访问密钥（AKIA…）",
                    Pattern.compile("AKIA[0-9A-Z]{16}")),
            new SecretPattern("私钥块（BEGIN PRIVATE KEY）",
                    Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY( BLOCK)?-----")),
            new SecretPattern("Slack 令牌（xox…）",
                    Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}")),
            new SecretPattern("GitHub 令牌（gh?_…）",
                    Pattern.compile("gh[pousr]_[A-Za-z0-9]{30,}")),
            new SecretPattern("Google API 密钥（AIza…）",
                    Pattern.compile("AIza[0-9A-Za-z_-]{35}"))
    );

    private SecretScanUtil() {
    }

    /**
     * 扫描内容是否疑似包含凭据。命中返回类型描述（如 "AWS 访问密钥（AKIA…）"），未命中返回 empty.
     */
    public static Optional<String> findSecret(String content) {
        if (content == null || content.isEmpty()) {
            return Optional.empty();
        }
        for (SecretPattern p : PATTERNS) {
            if (p.pattern().matcher(content).find()) {
                return Optional.of(p.type());
            }
        }
        return Optional.empty();
    }

    /** 便捷判定。 */
    public static boolean containsSecret(String content) {
        return findSecret(content).isPresent();
    }
}
