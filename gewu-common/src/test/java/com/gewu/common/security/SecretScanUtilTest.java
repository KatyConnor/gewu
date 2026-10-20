package com.gewu.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SecretScanUtil} 特征扫描单测（设计文档 56 §8.5）.
 */
class SecretScanUtilTest {

    @Test
    @DisplayName("OpenAI 风格 sk- 令牌命中")
    void openAiStyleToken() {
        assertThat(SecretScanUtil.containsSecret("我的key是 sk-abc123def456ghijklmn789 用于调用")).isTrue();
        // 短于 20 位不误报
        assertThat(SecretScanUtil.containsSecret("sk-short")).isFalse();
    }

    @Test
    @DisplayName("AWS AKIA 命中")
    void awsKey() {
        assertThat(SecretScanUtil.containsSecret("AKIAIOSFODNN7EXAMPLE")).isTrue();
    }

    @Test
    @DisplayName("私钥块命中")
    void privateKeyBlock() {
        assertThat(SecretScanUtil.containsSecret("-----BEGIN OPENSSH PRIVATE KEY-----\nabc")).isTrue();
        assertThat(SecretScanUtil.containsSecret("-----BEGIN RSA PRIVATE KEY-----")).isTrue();
    }

    @Test
    @DisplayName("Slack/GitHub/Google 特征命中")
    void otherVendors() {
        assertThat(SecretScanUtil.containsSecret("xoxb-1234567890-abcdef")).isTrue();
        assertThat(SecretScanUtil.containsSecret("ghp_" + "a".repeat(36))).isTrue();
        assertThat(SecretScanUtil.containsSecret("AIza" + "a".repeat(35))).isTrue();
    }

    @Test
    @DisplayName("正常规范内容不误报")
    void cleanContent() {
        String normal = "---\nversion: 1.0.0\n---\n# 项目规范\n- 构建命令: mvn test\n- 密钥三禁：不硬编码、不提交、不外传\n";
        assertThat(SecretScanUtil.containsSecret(normal)).isFalse();
        assertThat(SecretScanUtil.containsSecret("")).isFalse();
        assertThat(SecretScanUtil.containsSecret(null)).isFalse();
    }

    @Test
    @DisplayName("findSecret 返回类型描述")
    void findSecretReturnsType() {
        assertThat(SecretScanUtil.findSecret("AKIAIOSFODNN7EXAMPLE"))
                .isPresent()
                .get().asString().contains("AWS");
    }
}
