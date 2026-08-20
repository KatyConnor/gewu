package com.gewu.agent.engine.spi;

/**
 * 响应缓存 SPI - LLM 响应的语义级缓存。
 * <p>使用方实现此接口对接向量缓存（pgvector/Milvus/Redis 等）。
 * 框架提供默认空实现（不缓存）。
 *
 * @since 1.0.0
 */
public interface ResponseCache {

    /**
     * 查找语义缓存的响应。
     *
     * @param prompt  用户提示
     * @param contextKey 上下文键（如 agentId/domain）
     * @return 缓存命中返回响应内容，未命中返回 null
     */
    default String get(String prompt, String contextKey) {
        return null;
    }

    /**
     * 写入语义缓存。
     *
     * @param prompt  用户提示
     * @param response LLM 响应
     * @param contextKey 上下文键
     */
    default void put(String prompt, String response, String contextKey) {
    }
}