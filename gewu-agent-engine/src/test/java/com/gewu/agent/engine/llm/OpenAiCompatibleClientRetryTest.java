package com.gewu.agent.engine.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 受限重试判定单测（docs/design/47 问题三）：
 * 白名单 429/502/503/504 与网络 IOException 可重试；400/401 不重试；
 * 已收到首 token 后一律不重试（防重复产出）。
 */
class OpenAiCompatibleClientRetryTest {

    @Test
    @DisplayName("可重试状态码白名单")
    void retryableStatusWhitelist() {
        assertThat(OpenAiCompatibleClient.isRetryableStatus(429)).isTrue();
        assertThat(OpenAiCompatibleClient.isRetryableStatus(502)).isTrue();
        assertThat(OpenAiCompatibleClient.isRetryableStatus(503)).isTrue();
        assertThat(OpenAiCompatibleClient.isRetryableStatus(504)).isTrue();
        assertThat(OpenAiCompatibleClient.isRetryableStatus(400)).isFalse();
        assertThat(OpenAiCompatibleClient.isRetryableStatus(401)).isFalse();
        assertThat(OpenAiCompatibleClient.isRetryableStatus(500)).isFalse();
    }

    @Test
    @DisplayName("未出首 token：429 可重试、400 不可重试、网络 IO 异常可重试")
    void retryableBeforeFirstToken() {
        AtomicBoolean noToken = new AtomicBoolean(false);
        assertThat(OpenAiCompatibleClient.isRetryable(
                new OpenAiCompatibleClient.LlmStatusException(429, "限流", null), noToken)).isTrue();
        assertThat(OpenAiCompatibleClient.isRetryable(
                new OpenAiCompatibleClient.LlmStatusException(400, "参数", null), noToken)).isFalse();
        assertThat(OpenAiCompatibleClient.isRetryable(new IOException("connection reset"), noToken)).isTrue();
    }

    @Test
    @DisplayName("已收到首 token 后一律不重试")
    void neverRetryAfterFirstToken() {
        AtomicBoolean sawToken = new AtomicBoolean(true);
        assertThat(OpenAiCompatibleClient.isRetryable(
                new OpenAiCompatibleClient.LlmStatusException(429, "限流", "2"), sawToken)).isFalse();
        assertThat(OpenAiCompatibleClient.isRetryable(new IOException("reset"), sawToken)).isFalse();
    }

    @Test
    @DisplayName("Retry-After 头解析：秒转毫秒，非法为 0")
    void parseRetryAfterHeader() {
        assertThat(OpenAiCompatibleClient.parseRetryAfter("2")).isEqualTo(2000L);
        assertThat(OpenAiCompatibleClient.parseRetryAfter(" 3 ")).isEqualTo(3000L);
        assertThat(OpenAiCompatibleClient.parseRetryAfter(null)).isZero();
        assertThat(OpenAiCompatibleClient.parseRetryAfter("abc")).isZero();
    }
}
