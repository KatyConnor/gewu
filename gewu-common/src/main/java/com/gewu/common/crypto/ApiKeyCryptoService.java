package com.gewu.common.crypto;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;

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

    /** 仓库/示例文件中出现过的公开默认密钥——prod profile 命中时告警 */
    private static final Set<String> KNOWN_WEAK_SECRETS = Set.of(
            "0123456789abcdef0123456789abcdef",
            "13b4a0eab2f1f1093f87d0552fddca1f");

    @Value("${gewu.crypto.api-key-secret:}")
    private String secretHex;

    private final Environment environment;

    private byte[] key;

    public ApiKeyCryptoService(Environment environment) {
        this.environment = environment;
    }

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
        if (KNOWN_WEAK_SECRETS.contains(secretHex) && isProdProfile()) {
            log.warn("GEWU_CRYPTO_API_KEY_SECRET 使用了公开的示例默认值，生产环境必须替换为随机生成的 32 位十六进制密钥");
        }
        log.info("API Key 加密服务已初始化");
    }

    private boolean isProdProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equals(profile)) {
                return true;
            }
        }
        return false;
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
