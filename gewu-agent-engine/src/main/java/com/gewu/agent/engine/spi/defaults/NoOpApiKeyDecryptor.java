package com.gewu.agent.engine.spi.defaults;

import com.gewu.agent.engine.spi.ApiKeyDecryptor;

/**
 * {@link ApiKeyDecryptor} 的 NoOp 默认实现 - 原样透传（不解密）。
 *
 * @since 1.0.0
 */
public class NoOpApiKeyDecryptor implements ApiKeyDecryptor {

    @Override
    public String decrypt(String cipherText) {
        return cipherText;
    }
}
