package com.gewu.application.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.ai.dto.CreateModelCommand;
import com.gewu.application.ai.dto.CreateProviderCommand;
import com.gewu.application.ai.dto.ModelConfigDTO;
import com.gewu.application.ai.dto.ProviderDTO;
import com.gewu.application.ai.dto.UpdateModelCommand;
import com.gewu.application.ai.dto.UpdateProviderCommand;
import com.gewu.common.context.UserContext;
import com.gewu.common.crypto.ApiKeyCryptoService;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.ai.ModelConfig;
import com.gewu.domain.ai.ModelProvider;
import com.gewu.infrastructure.llm.LlmClientFactory;
import com.gewu.infrastructure.mapper.ModelConfigMapper;
import com.gewu.infrastructure.mapper.ModelProviderMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 模型配置服务 — 管理供应商和模型的 CRUD。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelConfigService {

    private final ModelProviderMapper providerMapper;
    private final ModelConfigMapper modelConfigMapper;
    private final LlmClientFactory llmClientFactory;
    private final ApiKeyCryptoService apiKeyCryptoService;
    private final ObjectMapper objectMapper;

    /**
     * 清除动态客户端缓存，使供应商配置变更生效。
     */
    private void clearDynamicClientCache(String providerCode) {
        try {
            llmClientFactory.evictClient(providerCode);
            log.info("已清除 LLM 客户端缓存: provider={}", providerCode);
        } catch (Exception e) {
            log.warn("清除 LLM 客户端缓存失败: provider={}, error={}", providerCode, e.getMessage());
        }
    }

    // ==================== 供应商 ====================

    /**
     * 获取所有供应商列表。
     */
    public List<ProviderDTO> listProviders() {
        List<ModelProvider> providers = providerMapper.selectList(
                new LambdaQueryWrapper<ModelProvider>()
                        .orderByAsc(ModelProvider::getSortOrder));

        // 统计每个供应商的模型数量
        List<ModelConfig> allModels = modelConfigMapper.selectList(null);
        Map<String, Long> countByProvider = allModels.stream()
                .collect(Collectors.groupingBy(ModelConfig::getProviderId, Collectors.counting()));

        return providers.stream()
                .map(p -> ProviderDTO.builder()
                        .id(p.getId())
                        .providerCode(p.getProviderCode())
                        .providerName(p.getProviderName())
                        .baseUrl(p.getBaseUrl())
                        .description(p.getDescription())
                        .logoLetter(p.getLogoLetter())
                        .logoColor(p.getLogoColor())
                        .textColor(p.getTextColor())
                        .status(p.getStatus())
                        .modelsCount(countByProvider.getOrDefault(p.getId(), 0L).intValue())
                        .build())
                .toList();
    }

    /**
     * 获取已启用的供应商列表。
     */
    public List<ProviderDTO> listActiveProviders() {
        List<ModelProvider> providers = providerMapper.selectList(
                new LambdaQueryWrapper<ModelProvider>()
                        .eq(ModelProvider::getStatus, 1)
                        .orderByAsc(ModelProvider::getSortOrder));

        return providers.stream()
                .map(p -> ProviderDTO.builder()
                        .id(p.getId())
                        .providerCode(p.getProviderCode())
                        .providerName(p.getProviderName())
                        .baseUrl(p.getBaseUrl())
                        .description(p.getDescription())
                        .logoLetter(p.getLogoLetter())
                        .logoColor(p.getLogoColor())
                        .textColor(p.getTextColor())
                        .status(p.getStatus())
                        .build())
                .toList();
    }

    /**
     * 创建供应商。
     */
    @Transactional
    public ProviderDTO createProvider(CreateProviderCommand command) {
        // 校验编码唯一性
        Long existCount = providerMapper.selectCount(
                new LambdaQueryWrapper<ModelProvider>()
                        .eq(ModelProvider::getProviderCode, command.getProviderCode()));
        if (existCount > 0) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "供应商编码已存在: " + command.getProviderCode());
        }

        ModelProvider provider = new ModelProvider();
        provider.setProviderCode(command.getProviderCode());
        provider.setProviderName(command.getProviderName());
        provider.setBaseUrl(command.getBaseUrl());
        provider.setApiKey(apiKeyCryptoService.encrypt(command.getApiKey()));
        provider.setDescription(command.getDescription());
        provider.setLogoLetter(command.getLogoLetter());
        provider.setLogoColor(command.getLogoColor());
        provider.setTextColor(command.getTextColor());
        provider.setStatus(command.getEnableImmediately() != null && command.getEnableImmediately() ? 1 : 2);
        provider.setSortOrder(0);

        providerMapper.insert(provider);
        log.info("创建供应商成功: code={}, name={}", command.getProviderCode(), command.getProviderName());

        return ProviderDTO.builder()
                .id(provider.getId())
                .providerCode(provider.getProviderCode())
                .providerName(provider.getProviderName())
                .baseUrl(provider.getBaseUrl())
                .description(provider.getDescription())
                .logoLetter(provider.getLogoLetter())
                .logoColor(provider.getLogoColor())
                .textColor(provider.getTextColor())
                .status(provider.getStatus())
                .modelsCount(0)
                .build();
    }

    /**
     * 更新供应商信息（providerCode 不可修改）。
     */
    @Transactional
    public ProviderDTO updateProvider(String providerId, UpdateProviderCommand command) {
        ModelProvider provider = providerMapper.selectById(providerId);
        if (provider == null) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "供应商不存在");
        }

        if (command.getProviderName() != null && !command.getProviderName().isBlank()) {
            provider.setProviderName(command.getProviderName());
        }
        if (command.getBaseUrl() != null && !command.getBaseUrl().isBlank()) {
            provider.setBaseUrl(command.getBaseUrl());
        }
        // API Key: 非空才更新（空字符串表示不修改）
        if (command.getApiKey() != null && !command.getApiKey().isBlank()) {
            provider.setApiKey(apiKeyCryptoService.encrypt(command.getApiKey()));
        }
        if (command.getDescription() != null) {
            provider.setDescription(command.getDescription());
        }
        if (command.getLogoLetter() != null) {
            provider.setLogoLetter(command.getLogoLetter());
        }
        if (command.getLogoColor() != null) {
            provider.setLogoColor(command.getLogoColor());
        }
        if (command.getTextColor() != null) {
            provider.setTextColor(command.getTextColor());
        }

        providerMapper.updateById(provider);
        log.info("更新供应商: id={}, name={}", providerId, provider.getProviderName());

        // 清除动态客户端缓存，使新配置生效
        clearDynamicClientCache(provider.getProviderCode());

        return ProviderDTO.builder()
                .id(provider.getId())
                .providerCode(provider.getProviderCode())
                .providerName(provider.getProviderName())
                .baseUrl(provider.getBaseUrl())
                .description(provider.getDescription())
                .logoLetter(provider.getLogoLetter())
                .logoColor(provider.getLogoColor())
                .textColor(provider.getTextColor())
                .status(provider.getStatus())
                .build();
    }

    /**
     * 切换供应商状态。
     */
    @Transactional
    public void toggleProviderStatus(String providerId) {
        ModelProvider provider = providerMapper.selectById(providerId);
        if (provider == null) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "供应商不存在");
        }
        provider.setStatus(provider.getStatus() == 1 ? 2 : 1);
        providerMapper.updateById(provider);
        log.info("切换供应商状态: id={}, status={}", providerId, provider.getStatus());
    }

    /**
     * 删除供应商。
     */
    @Transactional
    public void deleteProvider(String providerId) {
        // 同时删除关联的模型
        modelConfigMapper.delete(new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getProviderId, providerId));
        providerMapper.deleteById(providerId);
        log.info("删除供应商: id={}", providerId);
    }

    // ==================== 模型 ====================

    /**
     * 获取所有模型列表。
     */
    public List<ModelConfigDTO> listModels() {
        List<ModelConfig> models = modelConfigMapper.selectList(
                new LambdaQueryWrapper<ModelConfig>()
                        .orderByAsc(ModelConfig::getSortOrder));

        // 批量查询供应商信息
        List<ModelProvider> providers = providerMapper.selectList(null);
        Map<String, ModelProvider> providerMap = providers.stream()
                .collect(Collectors.toMap(ModelProvider::getId, p -> p));

        return models.stream()
                .map(m -> toModelConfigDTO(m, providerMap.get(m.getProviderId())))
                .toList();
    }

    /**
     * 获取已启用的模型列表（用于会话页面模型选择）。
     */
    public List<ModelConfigDTO> listActiveModels() {
        List<ModelConfig> models = modelConfigMapper.selectList(
                new LambdaQueryWrapper<ModelConfig>()
                        .eq(ModelConfig::getStatus, 1)
                        .orderByAsc(ModelConfig::getSortOrder));

        // 只查询已启用的供应商
        List<ModelProvider> providers = providerMapper.selectList(
                new LambdaQueryWrapper<ModelProvider>()
                        .eq(ModelProvider::getStatus, 1));
        Map<String, ModelProvider> providerMap = providers.stream()
                .collect(Collectors.toMap(ModelProvider::getId, p -> p));

        return models.stream()
                .filter(m -> providerMap.containsKey(m.getProviderId()))
                .map(m -> toModelConfigDTO(m, providerMap.get(m.getProviderId())))
                .toList();
    }

    /**
     * 创建模型。
     */
    @Transactional
    public ModelConfigDTO createModel(CreateModelCommand command) {
        // 校验供应商存在
        ModelProvider provider = providerMapper.selectById(command.getProviderId());
        if (provider == null) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "供应商不存在");
        }

        ModelConfig model = new ModelConfig();
        model.setProviderId(command.getProviderId());
        model.setModelName(command.getModelName());
        model.setModelId(command.getModelId());
        model.setModelParams(command.getModelParams());
        model.setDescription(command.getDescription());
        model.setStatus(command.getEnableImmediately() != null && command.getEnableImmediately() ? 1 : 2);
        model.setSortOrder(0);

        modelConfigMapper.insert(model);
        log.info("创建模型成功: provider={}, modelId={}", provider.getProviderCode(), command.getModelId());

        return toModelConfigDTO(model, provider);
    }

    /**
     * 更新模型信息（modelId 不可修改）。
     */
    @Transactional
    public ModelConfigDTO updateModel(String modelId, UpdateModelCommand command) {
        ModelConfig model = modelConfigMapper.selectById(modelId);
        if (model == null) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "模型不存在");
        }

        if (command.getProviderId() != null && !command.getProviderId().isBlank()) {
            // 校验供应商存在
            ModelProvider provider = providerMapper.selectById(command.getProviderId());
            if (provider == null) {
                throw BusinessException.of(ResultCode.PARAM_INVALID, "供应商不存在");
            }
            model.setProviderId(command.getProviderId());
        }
        if (command.getModelName() != null && !command.getModelName().isBlank()) {
            model.setModelName(command.getModelName());
        }
        if (command.getModelParams() != null) {
            model.setModelParams(command.getModelParams());
        }
        if (command.getDescription() != null) {
            model.setDescription(command.getDescription());
        }

        modelConfigMapper.updateById(model);
        log.info("更新模型: id={}, name={}", modelId, model.getModelName());

        ModelProvider provider = providerMapper.selectById(model.getProviderId());
        return toModelConfigDTO(model, provider);
    }

    /**
     * 切换模型状态。
     */
    @Transactional
    public void toggleModelStatus(String modelId) {
        ModelConfig model = modelConfigMapper.selectById(modelId);
        if (model == null) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "模型不存在");
        }
        model.setStatus(model.getStatus() == 1 ? 2 : 1);
        modelConfigMapper.updateById(model);
        log.info("切换模型状态: id={}, status={}", modelId, model.getStatus());
    }

    /**
     * 删除模型。
     */
    @Transactional
    public void deleteModel(String modelId) {
        modelConfigMapper.deleteById(modelId);
        log.info("删除模型: id={}", modelId);
    }

    // ==================== 工具方法 ====================

    /**
     * 根据模型 ID 查找供应商编码（供 LLM 客户端路由使用）。
     */
    public String getProviderCodeByModelId(String modelId) {
        ModelConfig model = modelConfigMapper.selectOne(
                new LambdaQueryWrapper<ModelConfig>()
                        .eq(ModelConfig::getModelId, modelId)
                        .eq(ModelConfig::getStatus, 1));
        if (model == null) {
            return null;
        }
        ModelProvider provider = providerMapper.selectById(model.getProviderId());
        return provider != null ? provider.getProviderCode() : null;
    }

    /**
     * 根据模型 ID 从 model_config.model_params JSON 中解析 max_tokens。
     * 推理模型（如 LongCat-2.0、DeepSeek-R1）的 reasoning_content token 计入 max_tokens，
     * 因此需要足够的额度让模型在推理后仍能输出正式回复。
     *
     * @param modelId 模型标识（如 LongCat-2.0、qwen-plus）
     * @return 配置的 max_tokens 值，未配置或解析失败返回 null（由调用方使用默认值）
     */
    public Integer getMaxTokensByModelId(String modelId) {
        try {
            ModelConfig model = modelConfigMapper.selectOne(
                    new LambdaQueryWrapper<ModelConfig>()
                            .eq(ModelConfig::getModelId, modelId)
                            .eq(ModelConfig::getStatus, 1));
            if (model == null || model.getModelParams() == null || model.getModelParams().isBlank()) {
                return null;
            }
            JsonNode params = objectMapper.readTree(model.getModelParams());
            if (params.has("max_tokens")) {
                int maxTokens = params.get("max_tokens").asInt();
                return maxTokens > 0 ? maxTokens : null;
            }
        } catch (Exception e) {
            log.warn("解析模型 max_tokens 失败: modelId={}, error={}", modelId, e.getMessage());
        }
        return null;
    }

    private ModelConfigDTO toModelConfigDTO(ModelConfig model, ModelProvider provider) {
        return ModelConfigDTO.builder()
                .id(model.getId())
                .providerId(model.getProviderId())
                .providerCode(provider != null ? provider.getProviderCode() : null)
                .providerName(provider != null ? provider.getProviderName() : null)
                .modelName(model.getModelName())
                .modelId(model.getModelId())
                .modelParams(model.getModelParams())
                .description(model.getDescription())
                .status(model.getStatus())
                .build();
    }
}