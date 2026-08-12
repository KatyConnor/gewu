package com.gewu.infrastructure.llm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.common.crypto.ApiKeyCryptoService;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.ai.ModelProvider;
import com.gewu.infrastructure.mapper.ModelProviderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 客户端工厂 — 动态创建和管理 LLM 客户端。
 * <p>
 * 优先从静态注册的专用客户端（qwen、deepseek）中查找；
 * 若未找到，则从数据库 model_provider 表动态加载供应商配置，
 * 创建 OpenAiCompatibleClient 并缓存，支持运行时新增的供应商。
 *
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClientFactory {

    /** 启动时注册的专用客户端（qwen、deepseek 等） */
    private final Map<String, LlmClient> staticClientMap;

    /** 运行时动态创建的客户端缓存 */
    private final Map<String, LlmClient> dynamicClientCache = new ConcurrentHashMap<>();

    private final ModelProviderMapper modelProviderMapper;
    private final HttpClient llmHttpClient;
    private final ObjectMapper llmObjectMapper;
    private final LlmRequestBodyBuilder bodyBuilder;
    private final ApiKeyCryptoService apiKeyCryptoService;

    /**
     * 获取 LLM 客户端。
     * <p>
     * 查找顺序：
     * <ol>
     *   <li>静态注册的专用客户端（qwen、deepseek）</li>
     *   <li>动态缓存中已创建的客户端</li>
     *   <li>从数据库加载供应商配置，创建 OpenAiCompatibleClient 并缓存</li>
     * </ol>
     *
     * @param provider 供应商编码（如 qwen、deepseek、zhipu、LongCat）
     * @return LLM 客户端实例
     * @throws BusinessException 当供应商不存在时抛出
     */
    public LlmClient getClient(String provider) {
        if (provider == null || provider.isBlank()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "LLM 提供商不能为空");
        }

        // 1. 优先从静态注册的专用客户端中查找
        LlmClient staticClient = staticClientMap.get(provider);
        if (staticClient != null) {
            return staticClient;
        }

        // 2. 从动态缓存中查找
        LlmClient cachedClient = dynamicClientCache.get(provider);
        if (cachedClient != null) {
            return cachedClient;
        }

        // 3. 从数据库加载供应商配置，动态创建客户端
        LlmClient dynamicClient = createClientFromDatabase(provider);
        if (dynamicClient != null) {
            dynamicClientCache.put(provider, dynamicClient);
            log.info("动态创建 LLM 客户端: provider={}", provider);
            return dynamicClient;
        }

        throw BusinessException.of(ResultCode.PARAM_INVALID, "不支持的 LLM 提供商: " + provider);
    }

    /**
     * 清除指定供应商的动态客户端缓存。
     * <p>
     * 当供应商配置（如 API Key、Base URL）变更时调用，使下次请求重新从数据库加载。
     *
     * @param providerCode 供应商编码
     */
    public void evictClient(String providerCode) {
        if (providerCode != null) {
            dynamicClientCache.remove(providerCode);
        }
    }

    /**
     * 从数据库加载供应商配置并创建客户端。
     *
     * @param providerCode 供应商编码
     * @return LLM 客户端实例，供应商不存在时返回 null
     */
    private LlmClient createClientFromDatabase(String providerCode) {
        try {
            ModelProvider provider = modelProviderMapper.selectOne(
                    new LambdaQueryWrapper<ModelProvider>()
                            .eq(ModelProvider::getProviderCode, providerCode)
                            .eq(ModelProvider::getStatus, 1));

            if (provider == null) {
                return null;
            }

            String baseUrl = provider.getBaseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                log.warn("供应商 {} 的 base_url 为空", providerCode);
                return null;
            }

            String apiKey = provider.getApiKey() != null ? provider.getApiKey() : "";
            String decryptedKey = apiKeyCryptoService.decrypt(apiKey);
            return new OpenAiCompatibleClient(
                    providerCode, decryptedKey, baseUrl, llmObjectMapper, llmHttpClient, bodyBuilder);
        } catch (Exception e) {
            log.warn("从数据库加载供应商 {} 失败: {}", providerCode, e.getMessage());
            return null;
        }
    }
}