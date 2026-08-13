package com.gewu.application.agent.adapter;

import com.gewu.agent.engine.llm.LlmProvider;
import com.gewu.agent.engine.llm.LlmProviderConfig;
import com.gewu.agent.engine.spi.ApiKeyDecryptor;
import com.gewu.common.crypto.ApiKeyCryptoService;
import com.gewu.domain.ai.ModelProvider;
import com.gewu.infrastructure.mapper.ModelProviderMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link LlmProvider} 业务适配 - 从 model_provider 表动态加载供应商配置。
 * <p>复用现有 ModelProviderMapper 与 ApiKeyCryptoService 解密，
 * 使框架 {@code LlmClientRegistry} 可运行时动态创建 OpenAI 兼容客户端。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class DbLlmProviderAdapter implements LlmProvider {

    private final ModelProviderMapper modelProviderMapper;
    private final ApiKeyCryptoService apiKeyCryptoService;

    @Override
    public LlmProviderConfig getProviderConfig(String providerCode) {
        try {
            ModelProvider provider = modelProviderMapper.selectOne(
                    new LambdaQueryWrapper<ModelProvider>()
                            .eq(ModelProvider::getProviderCode, providerCode)
                            .eq(ModelProvider::getStatus, 1));
            if (provider == null || provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
                return null;
            }
            String apiKey = provider.getApiKey() != null ? provider.getApiKey() : "";
            String decrypted = apiKeyCryptoService.decrypt(apiKey);
            return new LlmProviderConfig(providerCode, decrypted, provider.getBaseUrl());
        } catch (Exception e) {
            log.warn("从 model_provider 加载供应商 {} 失败: {}", providerCode, e.getMessage());
            return null;
        }
    }

    @Override
    public List<String> listProviderCodes() {
        return modelProviderMapper.selectList(
                        new LambdaQueryWrapper<ModelProvider>().eq(ModelProvider::getStatus, 1))
                .stream()
                .map(ModelProvider::getProviderCode)
                .toList();
    }
}