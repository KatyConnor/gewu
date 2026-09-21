package com.gewu.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ApiKeyCryptoService 单元测试。
 */
@ExtendWith(SpringExtension.class)
@TestPropertySource(properties = "gewu.crypto.api-key-secret=0123456789abcdef0123456789abcdef")
class ApiKeyCryptoServiceTest {

    private final ApiKeyCryptoService service = new ApiKeyCryptoService(new MockEnvironment());

    @org.junit.jupiter.api.BeforeEach
    void init() {
        ReflectionTestUtils.setField(service, "secretHex", "0123456789abcdef0123456789abcdef");
        service.init();
    }

    @Test
    @DisplayName("加密后应以 {SM4} 开头且可解密")
    void encryptThenDecrypt() {
        String plain = "sk-test123456789";
        String encrypted = service.encrypt(plain);

        assertThat(encrypted).startsWith("{SM4}");
        assertThat(encrypted).isNotEqualTo(plain);
        assertThat(service.decrypt(encrypted)).isEqualTo(plain);
    }

    @Test
    @DisplayName("重复加密不会二次加密")
    void encryptIsIdempotent() {
        String plain = "sk-test";
        String once = service.encrypt(plain);
        String twice = service.encrypt(once);

        assertThat(twice).isEqualTo(once);
        assertThat(service.decrypt(twice)).isEqualTo(plain);
    }

    @Test
    @DisplayName("空值保持空")
    void emptyValuePassthrough() {
        assertThat(service.encrypt(null)).isNull();
        assertThat(service.encrypt("")).isEmpty();
        assertThat(service.decrypt(null)).isNull();
        assertThat(service.decrypt("")).isEmpty();
    }

    @Test
    @DisplayName("明文应被识别为未加密")
    void isEncryptedFalseForPlaintext() {
        assertThat(service.isEncrypted("sk-plaintext")).isFalse();
    }

    @Test
    @DisplayName("非法密钥长度应启动失败")
    void invalidKeyLengthFailsOnInit() {
        ApiKeyCryptoService bad = new ApiKeyCryptoService(new MockEnvironment());
        ReflectionTestUtils.setField(bad, "secretHex", "tooshort");
        assertThatThrownBy(bad::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("密钥长度错误");
    }
}
