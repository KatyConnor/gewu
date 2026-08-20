package com.gewu.application.agent.adapter;

import com.gewu.agent.engine.spi.ResponseCache;
import com.gewu.infrastructure.cache.SemanticCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * ResponseCache SPI 适配器 - 桥接引擎响应缓存到 {@link SemanticCache}（pgvector 向量相似度缓存）。
 * <p>启用条件：{@code agent.engine.adapter.enabled=true}
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class SemanticResponseCacheAdapter implements ResponseCache {

    private final SemanticCache semanticCache;

    @Override
    public String get(String prompt, String contextKey) {
        try {
            return semanticCache.get(prompt, Map.of("context", contextKey != null ? contextKey : "default"),
                    contextKey != null ? contextKey : "default");
        } catch (Exception e) {
            log.debug("SemanticResponseCacheAdapter.get failed: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void put(String prompt, String response, String contextKey) {
        try {
            semanticCache.put(prompt, response, contextKey != null ? contextKey : "default");
        } catch (Exception e) {
            log.debug("SemanticResponseCacheAdapter.put failed: {}", e.getMessage());
        }
    }
}