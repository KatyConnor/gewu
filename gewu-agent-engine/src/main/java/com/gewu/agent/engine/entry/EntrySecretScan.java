package com.gewu.agent.engine.entry;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 入口文本密钥特征扫描（引擎本地最小实现）.
 *
 * <p>特征集与 gewu-common 的 SecretScanUtil 保持一致（引擎自包含、不依赖
 * gewu-common，两处修改需同步）。用于子目录嵌套入口附加前的把关
 * （设计文档 57 §6）：命中则以一行警示替代内容注入。
 */
public final class EntrySecretScan {

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

    private EntrySecretScan() {
    }

    /** 命中返回类型描述，未命中返回 empty。 */
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
}
