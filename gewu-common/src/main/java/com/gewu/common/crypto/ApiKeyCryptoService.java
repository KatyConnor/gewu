package com.gewu.common.crypto;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * API Key 等敏感数据加密服务 — 基于 SM4-GCM.
 *
 * <p>密文格式：{@code {SM4}<hex>}，便于与存量明文区分。
 * 若配置密钥未提供，则启动失败，强制要求生产环境配置。
 */
@Slf4j
@Component
public class ApiKeyCryptoService {

    private static final String CIPHER_PREFIX = "{SM4}";

    @Value("${gewu.crypto.api-key-secret:}")
    private String secretHex;

    private byte[] key;

    @PostConstruct
    public void init() {
        if (secretHex == null || secretHex.isBlank()) {
            throw new IllegalStateException(
                    "缺少敏感数据加密密钥：请配置环境变量 GEWU_CRYPTO_API_KEY_SECRET（32 位十六进制字符串，对应 16 字节 SM4 密钥）");
        }
        if (secretHex.length() != 32) {
            throw new IllegalStateException(
                    "敏感数据加密密钥长度错误：需为 32 位十六进制字符串，当前长度=" + secretHex.length());
        }
        this.key = SM4Util.hexToBytes(secretHex);
        log.info("API Key 加密服务已初始化");
    }

    /**
     * 加密明文。空值直接返回。
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }
        if (isEncrypted(plaintext)) {
            return plaintext;
        }
        String ciphertext = SM4Util.encryptHex(key, plaintext.getBytes(StandardCharsets.UTF_8));
        return CIPHER_PREFIX + ciphertext;
    }

    /**
     * 解密密文。非密文格式直接返回原文（兼容存量明文）。
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return ciphertext;
        }
        if (!isEncrypted(ciphertext)) {
            return ciphertext;
        }
        String hex = ciphertext.substring(CIPHER_PREFIX.length());
        return SM4Util.decryptToString(key, hex);
    }

    /**
     * 判断字符串是否已是密文格式。
     */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(CIPHER_PREFIX);
    }
}
