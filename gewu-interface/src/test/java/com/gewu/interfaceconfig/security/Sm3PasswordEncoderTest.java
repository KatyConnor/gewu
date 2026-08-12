package com.gewu.interfaceconfig.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sm3PasswordEncoder 单元测试。
 */
class Sm3PasswordEncoderTest {

    private final Sm3PasswordEncoder encoder = new Sm3PasswordEncoder();

    @Test
    @DisplayName("编码后格式正确且可验证")
    void encodeAndVerify() {
        String raw = "myPassword123";
        String encoded = encoder.encode(raw);

        assertThat(encoded).contains("$");
        assertThat(encoder.matches(raw, encoded)).isTrue();
        assertThat(encoder.matches("wrongPassword", encoded)).isFalse();
    }

    @Test
    @DisplayName("空或非法编码不匹配")
    void matchesNullOrBlank() {
        assertThat(encoder.matches("any", null)).isFalse();
        assertThat(encoder.matches("any", "")).isFalse();
        assertThat(encoder.matches("any", "not-valid")).isFalse();
    }
}
