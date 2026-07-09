package com.gewu.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class SM3UtilTest {

    private static final Pattern HEX_64 = Pattern.compile("^[0-9a-f]{64}$");

    @Test
    @DisplayName("已知输入 abc 的 SM3 哈希值匹配标准测试向量")
    void hashOfKnownInputMatchesExpectedHex() {
        String result = SM3Util.hashHex("abc");
        assertThat(result).isEqualTo("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0");
    }

    @Test
    @DisplayName("hashHex 返回 64 字符的十六进制字符串")
    void hashHexReturns64CharHexString() {
        String result = SM3Util.hashHex("hello world");
        assertThat(result).hasSize(64);
        assertThat(HEX_64.matcher(result).matches()).isTrue();
    }

    @Test
    @DisplayName("hashWithSalt 加盐哈希与无盐哈希输出不同")
    void hashWithSaltProducesDifferentOutputThanHashWithoutSalt() {
        String data = "password123";
        byte[] salt = "random-salt-value".getBytes(StandardCharsets.UTF_8);
        String withoutSalt = SM3Util.hashHex(data);
        String withSalt = SM3Util.hashWithSalt(data, salt);
        assertThat(withSalt).isNotEqualTo(withoutSalt);
        assertThat(withSalt).hasSize(64);
    }

    @Test
    @DisplayName("verify 对正确哈希返回 true，对错误哈希返回 false")
    void verifyReturnsTrueForCorrectHashAndFalseForIncorrect() {
        String data = "test-data";
        String correctHash = SM3Util.hashHex(data);
        assertThat(SM3Util.verify(data, correctHash)).isTrue();
        assertThat(SM3Util.verify(data, "0000000000000000000000000000000000000000000000000000000000000000")).isFalse();
    }

    @Test
    @DisplayName("verify 忽略大小写差异")
    void verifyIsCaseInsensitive() {
        String data = "test-data";
        String upperHash = SM3Util.hashHex(data).toUpperCase();
        assertThat(SM3Util.verify(data, upperHash)).isTrue();
    }

    @Test
    @DisplayName("hash(byte[]) 与 hash(String) 对相同输入返回相同结果")
    void hashByteArrayMatchesHashString() {
        String data = "一致性测试";
        byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
        String fromString = SM3Util.bytesToHex(SM3Util.hash(data));
        String fromBytes = SM3Util.bytesToHex(SM3Util.hash(dataBytes));
        assertThat(fromString).isEqualTo(fromBytes);
    }

    @Test
    @DisplayName("空字符串哈希返回 64 字符结果")
    void hashEmptyStringReturns64CharResult() {
        String result = SM3Util.hashHex("");
        assertThat(result).hasSize(64);
        assertThat(SM3Util.verify("", result)).isTrue();
    }

    @Test
    @DisplayName("bytesToHex 正确将字节数组转换为十六进制字符串")
    void bytesToHexConvertsCorrectly() {
        byte[] bytes = {0x00, (byte) 0xFF, 0x1A, 0x7F};
        String hex = SM3Util.bytesToHex(bytes);
        assertThat(hex).isEqualTo("00ff1a7f");
    }
}
