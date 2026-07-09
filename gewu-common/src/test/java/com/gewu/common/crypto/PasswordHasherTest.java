package com.gewu.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTest {

    @Test
    @DisplayName("hash 返回非空字符串")
    void hashReturnsNonNullString() {
        String hashed = PasswordHasher.hash("myPassword123");
        assertThat(hashed).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("hash 格式包含 $ 分隔符（3 部分）")
    void hashFormatContainsThreePartsSeparatedByDollar() {
        String hashed = PasswordHasher.hash("myPassword123");
        String[] parts = hashed.split("\\$");
        assertThat(parts).hasSize(3);
        assertThat(parts[0]).isEqualTo("10000");
        assertThat(parts[1]).hasSize(64);
        assertThat(parts[2]).hasSize(64);
    }

    @Test
    @DisplayName("verify 对正确密码返回 true")
    void verifyReturnsTrueForCorrectPassword() {
        String password = "correctPassword!@#";
        String hashed = PasswordHasher.hash(password);
        assertThat(PasswordHasher.verify(password, hashed)).isTrue();
    }

    @Test
    @DisplayName("verify 对错误密码返回 false")
    void verifyReturnsFalseForWrongPassword() {
        String hashed = PasswordHasher.hash("correctPassword");
        assertThat(PasswordHasher.verify("wrongPassword", hashed)).isFalse();
    }

    @Test
    @DisplayName("对同一密码两次 hash 产生不同结果（随机盐）")
    void hashSamePasswordTwiceProducesDifferentResults() {
        String password = "samePassword";
        String hash1 = PasswordHasher.hash(password);
        String hash2 = PasswordHasher.hash(password);
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("verify 对格式错误的存储哈希返回 false")
    void verifyReturnsFalseForMalformedStoredHash() {
        assertThat(PasswordHasher.verify("password", "malformed-hash")).isFalse();
        assertThat(PasswordHasher.verify("password", "a$b$c$d")).isFalse();
    }

    @Test
    @DisplayName("generateSalt 返回 32 字节盐值")
    void generateSaltReturns32Bytes() {
        byte[] salt = PasswordHasher.generateSalt();
        assertThat(salt).hasSize(32);
    }

    @Test
    @DisplayName("generateSalt 每次生成不同的盐值")
    void generateSaltProducesDifferentSalts() {
        byte[] salt1 = PasswordHasher.generateSalt();
        byte[] salt2 = PasswordHasher.generateSalt();
        assertThat(salt1).isNotEqualTo(salt2);
    }
}
