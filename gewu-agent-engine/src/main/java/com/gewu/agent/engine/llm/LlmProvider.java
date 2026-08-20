package com.gewu.agent.engine.llm;

import java.util.List;

/**
 * LLM 供应商动态配置源 SPI。
 * <p>使用方实现此接口，从数据库 / 配置中心 / 文件等任意来源提供 LLM 供应商配置。
 * 框架 {@link LlmClientRegistry} 在静态注册的客户端未命中时，通过此接口动态创建 {@link OpenAiCompatibleClient}。
 * <p>未提供实现时框架使用 {@code NoOpLlmProvider}（返回空），此时仅代码注册的客户端可用。
 *
 * @since 1.0.0
 */
public interface LlmProvider {

    /** 获取指定供应商的配置，未配置返回 null */
    LlmProviderConfig getProviderConfig(String providerCode);

    /** 列出所有动态供应商编码（用于预热 / 校验） */
    default List<String> listProviderCodes() {
        return List.of();
    }
}
