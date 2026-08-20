package com.gewu.agent.engine.llm;

/**
 * LLM 供应商配置（由 {@link LlmProvider} SPI 返回）。
 * <p>apiKey 应为已解密的明文（解密由使用方在实现 {@link LlmProvider} 时完成，
 * 可借助 {@code ApiKeyDecryptor} SPI）。
 *
 * @param providerCode 供应商标识
 * @param apiKey       API Key（已解密明文）
 * @param baseUrl      接口地址（Chat Completions endpoint）
 * @since 1.0.0
 */
public record LlmProviderConfig(String providerCode, String apiKey, String baseUrl) {
}
