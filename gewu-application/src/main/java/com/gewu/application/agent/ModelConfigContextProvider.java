package com.gewu.application.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.agent.engine.spi.ModelContextProvider;
import com.gewu.domain.ai.ModelConfig;
import com.gewu.infrastructure.mapper.ModelConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型上下文窗口宿主实现：读取 model_config.context_window_input / context_window_output
 * （管理员在模型配置中维护），带 5 分钟本地缓存（模型配置变更低频）。
 * 未知模型/未配置返回 null（引擎跳过压缩与输出上限校验）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelConfigContextProvider implements ModelContextProvider {

    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private final ModelConfigMapper modelConfigMapper;

    private record ContextSpec(Integer input, Integer output) {}

    private final ConcurrentHashMap<String, CachedSpec> cache = new ConcurrentHashMap<>();

    @Override
    public Integer contextWindowInput(String modelId) {
        ContextSpec spec = specOf(modelId);
        return spec != null ? spec.input() : null;
    }

    @Override
    public Integer contextWindowOutput(String modelId) {
        ContextSpec spec = specOf(modelId);
        return spec != null ? spec.output() : null;
    }

    private ContextSpec specOf(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        long now = System.currentTimeMillis();
        CachedSpec cached = cache.get(modelId);
        if (cached != null && now < cached.expiresAt) {
            return cached.spec;
        }
        ContextSpec spec = load(modelId);
        cache.put(modelId, new CachedSpec(spec, now + CACHE_TTL_MS));
        return spec;
    }

    private ContextSpec load(String modelId) {
        try {
            List<ModelConfig> configs = modelConfigMapper.selectList(
                    new LambdaQueryWrapper<ModelConfig>()
                            .eq(ModelConfig::getModelId, modelId)
                            .eq(ModelConfig::getStatus, 1)
                            .last("LIMIT 1"));
            if (configs.isEmpty()) {
                return null;
            }
            ModelConfig config = configs.get(0);
            Integer input = config.getContextWindowInput() != null && config.getContextWindowInput() > 0
                    ? config.getContextWindowInput() : null;
            Integer output = config.getContextWindowOutput() != null && config.getContextWindowOutput() > 0
                    ? config.getContextWindowOutput() : null;
            return new ContextSpec(input, output);
        } catch (Exception e) {
            log.warn("模型上下文窗口查询失败（按未知处理）: model={}, cause={}", modelId, e.getMessage());
            return null;
        }
    }

    private record CachedSpec(ContextSpec spec, long expiresAt) {}
}
