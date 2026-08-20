package com.gewu.agent.engine.spi;

/**
 * API Key 解密器 SPI。
 * <p>使用方实现此接口，对 LLM 供应商的 API Key 进行解密（如国密 SM4 / AES）。
 * 框架提供 {@code NoOpApiKeyDecryptor} 默认实现（原样透传）。
 * <p>使用方在实现 {@link com.gewu.agent.engine.llm.LlmProvider} 时可注入此接口完成解密。
 *
 * @since 1.0.0
 */
public interface ApiKeyDecryptor {

    /** 解密 API Key，未加密则原样返回 */
    String decrypt(String cipherText);
}
