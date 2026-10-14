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
    @DisplayName("hash 产出 PBKDF2 新格式（4 段：PBKDF2$迭代$盐B64$哈希B64）")
    void hashProducesPbkdf2Format() {
        // M-6 行为升级同步（原断言为旧格式 3 段/10000/hex64）：新哈希一律 PBKDF2 格式
        String hashed = PasswordHasher.hash("myPassword123");
        String[] parts = hashed.split("\\$");
        assertThat(parts).hasSize(4);
        assertThat(parts[0]).isEqualTo("PBKDF2");
        assertThat(Integer.parseInt(parts[1])).isGreaterThanOrEqualTo(100_000);
        assertThat(java.util.Base64.getDecoder().decode(parts[2])).hasSize(32);
        assertThat(java.util.Base64.getDecoder().decode(parts[3])).hasSize(32);
    }

    @Test
    @DisplayName("verify 兼容旧格式（SM3 加盐后串接迭代，配合登录渐进升级）")
    void verifySupportsLegacyFormat() {
        // 旧格式本体与 PasswordHasher.legacySm3Chain 同型：hashWithSalt 一轮 + hashHex 串接迭代
        String saltHex = "aabb".repeat(16);
        String current = com.gewu.common.crypto.SM3Util.hashWithSalt("legacyPwd",
                java.util.HexFormat.of().parseHex(saltHex));
        for (int i = 1; i < 10000; i++) {
            current = com.gewu.common.crypto.SM3Util.hashHex(current);
        }
        String legacy = "10000$" + saltHex + "$" + current;
        assertThat(PasswordHasher.verify("legacyPwd", legacy)).isTrue();
        assertThat(PasswordHasher.verify("wrongPwd", legacy)).isFalse();
        assertThat(PasswordHasher.needsUpgrade(legacy)).isTrue();
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
