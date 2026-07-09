package com.gewu.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SM4UtilTest {

    @Test
    @DisplayName("加密后解密返回原始明文")
    void encryptThenDecryptReturnsOriginalPlaintext() {
        byte[] key = SM4Util.generateKey();
        String plaintext = "Hello, 格物平台!";
        String encrypted = SM4Util.encryptHex(key, plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] decrypted = SM4Util.decrypt(key, encrypted);
        assertThat(new String(decrypted, StandardCharsets.UTF_8)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("decryptToString 返回原始明文字符串")
    void decryptToStringReturnsOriginalPlaintext() {
        byte[] key = SM4Util.generateKey();
        String plaintext = "SM4-GCM-Test-数据";
        String encrypted = SM4Util.encryptHex(key, plaintext.getBytes(StandardCharsets.UTF_8));
        assertThat(SM4Util.decryptToString(key, encrypted)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("相同输入加密两次产生不同密文（随机 IV）")
    void encryptProducesDifferentOutputForSameInput() {
        byte[] key = SM4Util.generateKey();
        byte[] plaintext = "same-input-data".getBytes(StandardCharsets.UTF_8);
        String encrypted1 = SM4Util.encryptHex(key, plaintext);
        String encrypted2 = SM4Util.encryptHex(key, plaintext);
        assertThat(encrypted1).isNotEqualTo(encrypted2);
    }

    @Test
    @DisplayName("使用错误密钥解密抛出 IllegalStateException")
    void decryptWithWrongKeyThrowsException() {
        byte[] key = SM4Util.generateKey();
        byte[] wrongKey = SM4Util.generateKey();
        String encrypted = SM4Util.encryptHex(key, "secret-data".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> SM4Util.decrypt(wrongKey, encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SM4-GCM 解密失败");
    }

    @Test
    @DisplayName("generateKey 返回 16 字节密钥")
    void generateKeyReturns16ByteKey() {
        byte[] key = SM4Util.generateKey();
        assertThat(key).hasSize(16);
    }

    @Test
    @DisplayName("generateKey 每次生成不同的密钥")
    void generateKeyProducesDifferentKeys() {
        byte[] key1 = SM4Util.generateKey();
        byte[] key2 = SM4Util.generateKey();
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    @DisplayName("hexToBytes 正确解析十六进制字符串")
    void hexToBytesParsesCorrectly() {
        byte[] result = SM4Util.hexToBytes("00ff1a7f");
        assertThat(result).containsExactly(0x00, (byte) 0xFF, 0x1A, 0x7F);
    }

    @Test
    @DisplayName("加密结果包含 IV 前缀（至少 12 字节 IV + 密文）")
    void encryptedHexContainsIvAndCiphertext() {
        byte[] key = SM4Util.generateKey();
        byte[] plaintext = "test".getBytes(StandardCharsets.UTF_8);
        String encrypted = SM4Util.encryptHex(key, plaintext);
        byte[] combined = SM4Util.hexToBytes(encrypted);
        assertThat(combined.length).isGreaterThan(12);
    }

    @Test
    @DisplayName("对空明文加密后解密返回空字节数组")
    void encryptEmptyPlaintextThenDecryptReturnsEmpty() {
        byte[] key = SM4Util.generateKey();
        String encrypted = SM4Util.encryptHex(key, new byte[0]);
        byte[] decrypted = SM4Util.decrypt(key, encrypted);
        assertThat(decrypted).isEmpty();
    }
}
