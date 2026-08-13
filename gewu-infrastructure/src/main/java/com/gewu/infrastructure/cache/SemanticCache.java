package com.gewu.infrastructure.cache;

import com.gewu.infrastructure.wenshi.adapter.EmbeddingAdapter;
import com.gewu.infrastructure.wenshi.adapter.VectorStoreAdapter;
import com.gewu.infrastructure.wenshi.adapter.VectorStoreAdapter.VectorFragment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义缓存 - 通过向量相似度查找可复用的 LLM 响应。
 * <p>非精确匹配，以 Embedding 相似度 ≥ 阈值判定缓存命中。
 * 按场景决定 TTL：知识问答 24h / 代码生成 1h / 默认 5min。
 *
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticCache {

    private final EmbeddingAdapter embeddingAdapter;
    private final VectorStoreAdapter vectorStoreAdapter;

    /** 缓存命中相似度阈值 */
    private static final double SIMILARITY_THRESHOLD = 0.95;

    /** 缓存命名空间（在 wenshi_semantic_fragment 中用 source 隔离） */
    private static final String CACHE_SOURCE = "SEMANTIC_CACHE";

    /**
     * 查找语义缓存。
     *
     * @param prompt    用户提示
     * @param context   上下文（用于生成缓存键）
     * @param tenantId  租户 ID
     * @return 缓存命中则返回响应内容，未命中返回 null
     */
    public String get(String prompt, Map<String, Object> context, String tenantId) {
        if (prompt == null || prompt.isBlank()) return null;
        try {
            float[] queryVector = embeddingAdapter.embed(prompt);
            Map<String, Object> filters = new HashMap<>();
            filters.put("tenantId", tenantId != null ? tenantId : "default");
            filters.put("source", CACHE_SOURCE);

            List<VectorFragment> candidates = vectorStoreAdapter.search(queryVector, 3, filters);
            for (VectorFragment candidate : candidates) {
                double similarity = cosineSimilarity(queryVector, embeddingAdapter.embed(candidate.getContent()));
                if (similarity >= SIMILARITY_THRESHOLD) {
                    // 缓存内容的后半部分是响应（以特殊分隔符存储）
                    String content = candidate.getContent();
                    int sep = content.indexOf("\n===CACHE_RESPONSE===\n");
                    if (sep > 0) {
                        String response = content.substring(sep + "\n===CACHE_RESPONSE===\n".length());
                        log.debug("SemanticCache 命中: similarity={}", similarity);
                        return response;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("SemanticCache.get failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 写入语义缓存。
     *
     * @param prompt    用户提示
     * @param response  LLM 响应
     * @param tenantId  租户 ID
     */
    public void put(String prompt, String response, String tenantId) {
        if (prompt == null || prompt.isBlank() || response == null || response.isBlank()) return;
        try {
            float[] vector = embeddingAdapter.embed(prompt);
            // 缓存内容格式：prompt\n===CACHE_RESPONSE===\nresponse
            String content = prompt + "\n===CACHE_RESPONSE===\n" + response;
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("tenantId", tenantId != null ? tenantId : "default");
            metadata.put("source", CACHE_SOURCE);

            vectorStoreAdapter.upsert(List.of(VectorFragment.builder()
                    .id(java.util.UUID.randomUUID().toString())
                    .content(content)
                    .embedding(vector)
                    .metadata(metadata)
                    .build()));
            log.debug("SemanticCache 写入: promptLength={}, responseLength={}", prompt.length(), response.length());
        } catch (Exception e) {
            log.debug("SemanticCache.put failed: {}", e.getMessage());
        }
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom > 0 ? dot / denom : 0;
    }
}