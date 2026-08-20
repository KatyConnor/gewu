package com.gewu.agent.engine.llm;

import com.gewu.agent.engine.AgentEngineException;
import lombok.extern.slf4j.Slf4j;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * LLM 客户端注册中心 - 替代工厂模式，管理与路由多供应商客户端。
 * <p>查找顺序：
 * <ol>
 *   <li>静态注册的 {@link LlmClient} Bean（代码注册的专用客户端）</li>
 *   <li>动态缓存中已创建的客户端</li>
 *   <li>通过 {@link LlmProvider} SPI 获取供应商配置，动态创建 {@link OpenAiCompatibleClient} 并缓存</li>
 * </ol>
 * 不绑定数据库——动态配置来源由使用方实现 {@link LlmProvider} SPI 提供。
 *
 * @since 1.0.0
 */
@Slf4j
public class LlmClientRegistry {

    /** 静态注册的专用客户端（Spring 注入的 LlmClient Bean，按 provider 索引） */
    private final Map<String, LlmClient> staticClients;

    /** 运行时动态创建的客户端缓存 */
    private final Map<String, LlmClient> dynamicCache = new ConcurrentHashMap<>();

    private final LlmProvider provider;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final LlmRequestBodyBuilder bodyBuilder;

    public LlmClientRegistry(List<LlmClient> clients, LlmProvider provider,
                              ObjectMapper objectMapper, HttpClient httpClient,
                              LlmRequestBodyBuilder bodyBuilder) {
        this.staticClients = clients.stream()
                .collect(Collectors.toUnmodifiableMap(LlmClient::getProvider, c -> c, (a, b) -> a));
        this.provider = provider;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.bodyBuilder = bodyBuilder;
    }

    /**
     * 获取 LLM 客户端。
     *
     * @param provider 供应商编码（如 qwen、deepseek）
     * @return LLM 客户端实例
     * @throws AgentEngineException 当供应商不存在时
     */
    public LlmClient getClient(String provider) {
        if (provider == null || provider.isBlank()) {
            throw AgentEngineException.of("PARAM_INVALID", "LLM 提供商不能为空");
        }

        LlmClient staticClient = staticClients.get(provider);
        if (staticClient != null) {
            return staticClient;
        }

        LlmClient cachedClient = dynamicCache.get(provider);
        if (cachedClient != null) {
            return cachedClient;
        }

        LlmClient dynamicClient = createDynamicClient(provider);
        if (dynamicClient != null) {
            dynamicCache.put(provider, dynamicClient);
            log.info("动态创建 LLM 客户端: provider={}", provider);
            return dynamicClient;
        }

        throw AgentEngineException.of("PROVIDER_NOT_FOUND", "不支持的 LLM 提供商: " + provider);
    }

    /** 清除指定供应商的动态客户端缓存（配置变更时调用） */
    public void evictClient(String providerCode) {
        if (providerCode != null) {
            dynamicCache.remove(providerCode);
        }
    }

    /** 列出所有可用供应商编码（静态 + 动态） */
    public List<String> listProviderCodes() {
        var codes = new java.util.ArrayList<>(staticClients.keySet());
        codes.addAll(dynamicCache.keySet());
        if (provider != null) {
            codes.addAll(provider.listProviderCodes());
        }
        return codes.stream().distinct().toList();
    }

    private LlmClient createDynamicClient(String providerCode) {
        if (provider == null) {
            return null;
        }
        try {
            LlmProviderConfig config = provider.getProviderConfig(providerCode);
            if (config == null || config.baseUrl() == null || config.baseUrl().isBlank()) {
                return null;
            }
            String apiKey = config.apiKey() != null ? config.apiKey() : "";
            return new OpenAiCompatibleClient(
                    providerCode, apiKey, config.baseUrl(), objectMapper, httpClient, bodyBuilder);
        } catch (Exception e) {
            log.warn("从 LlmProvider 加载供应商 {} 失败: {}", providerCode, e.getMessage());
            return null;
        }
    }
}
