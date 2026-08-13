package com.gewu.application.agent.adapter;

import com.gewu.agent.engine.spi.ApiKeyDecryptor;
import com.gewu.common.crypto.ApiKeyCryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * {@link ApiKeyDecryptor} 业务适配 - 桥接框架与现有 {@link ApiKeyCryptoService}（国密 SM4 解密）。
 *
 * @since 1.0.0
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class SmApiKeyDecryptorAdapter implements ApiKeyDecryptor {

    private final ApiKeyCryptoService apiKeyCryptoService;

    @Override
    public String decrypt(String cipherText) {
        return apiKeyCryptoService.decrypt(cipherText);
    }
}