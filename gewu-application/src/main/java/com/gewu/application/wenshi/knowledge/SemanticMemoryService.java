package com.gewu.application.wenshi.knowledge;

import com.gewu.domain.wenshi.knowledge.SemanticFragment;
import com.gewu.infrastructure.cache.CacheService;
import com.gewu.infrastructure.mapper.wenshi.SemanticFragmentMapper;
import com.gewu.infrastructure.wenshi.adapter.EmbeddingAdapter;
import com.gewu.infrastructure.wenshi.adapter.VectorStoreAdapter;
import com.gewu.infrastructure.wenshi.adapter.VectorStoreAdapter.VectorFragment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义记忆服务 - 管理业务知识、事实、概念的向量化存储与语义检索。
 * <p>
 * 知识写入时通过 {@link EmbeddingAdapter} 生成向量，经 {@link VectorStoreAdapter}
 *（pgvector）持久化向量列；检索时按余弦相似度（<=> ）返回最相关的片段。
 * 查询结果使用 Redis 缓存（TTL 10 分钟），写入时自动失效相关缓存。
 * 仅当 topK 小于等于 {@value #CACHE_THRESHOLD} 时才启用缓存，避免大数据量查询占用过多内存。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
public class SemanticMemoryService {

    /** 语义检索结果的缓存有效期（10 分钟）。 */
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    /** 启用缓存的最大 topK 阈值，超过此值不缓存以避免内存压力。 */
    private static final int CACHE_THRESHOLD = 3;

    private final SemanticFragmentMapper mapper;
    private final EmbeddingAdapter embeddingAdapter;
    private final CacheService cacheService;

    /** 向量存储适配器（pgvector），当 wenshi 数据源不可用时为 null（回退到时间排序） */
    private final VectorStoreAdapter vectorStoreAdapter;

    public SemanticMemoryService(SemanticFragmentMapper mapper,
                                  EmbeddingAdapter embeddingAdapter,
                                  CacheService cacheService,
                                  @org.springframework.beans.factory.annotation.Autowired(required = false) VectorStoreAdapter vectorStoreAdapter) {
        this.mapper = mapper;
        this.embeddingAdapter = embeddingAdapter;
        this.cacheService = cacheService;
        this.vectorStoreAdapter = vectorStoreAdapter;
    }

    /**
     * 将一段知识内容写入语义记忆。
     * <p>
     * 写入流程：(1) 通过 EmbeddingAdapter 生成 384 维向量；(2) 通过 MyBatis-Plus 写入基础字段；
     * (3) 通过 VectorStoreAdapter（pgvector）写入 embedding 列。
     * 写入后自动失效该租户下所有语义检索缓存。
     *
     * @param tenantId 租户 ID，用于数据隔离
     * @param userId   操作用户 ID，记录创建人
     * @param content  知识文本内容，不可为空
     * @param source   知识来源标识（如 MANUAL、DIALOGUE），为空时默认为 "MANUAL"
     * @param metadata 附加元数据，可为 null
     * @return 持久化后的 {@link SemanticFragment} 实体（含生成的 ID 与向量）
     * @since 1.0.0
     */
    public SemanticFragment ingest(String tenantId, String userId, String content, String source, Map<String, Object> metadata) {
        SemanticFragment fragment = new SemanticFragment();
        fragment.setId(com.gewu.common.ulid.Ulid.next());
        fragment.setTenantId(tenantId);
        fragment.setOwnerUserId(userId);
        fragment.setContent(content);
        fragment.setSource(source != null ? source : "MANUAL");
        fragment.setConfidence(BigDecimal.ONE);
        String metadataStr = metadata != null ? metadata.toString() : null;
        fragment.setMetadata(metadataStr);
        fragment.setCreatedBy(userId);
        fragment.setUpdatedBy(userId);

        // 生成向量嵌入
        float[] vector = embeddingAdapter.embed(content);
        fragment.setEmbedding(vector);

        // 写入基础字段（embedding 列为 NULL，由下方 upsert 填充）
        mapper.insert(fragment);

        // 通过 pgvector 写入 embedding 列（wenshi 数据源不可用时跳过）
        if (vectorStoreAdapter != null) {
            Map<String, Object> upsertMeta = new HashMap<>();
            upsertMeta.put("tenantId", tenantId);
            upsertMeta.put("userId", userId);
            upsertMeta.put("source", fragment.getSource());
            upsertMeta.put("metadata", metadataStr);
            try {
                vectorStoreAdapter.upsert(List.of(VectorFragment.builder()
                        .id(fragment.getId())
                        .content(content)
                        .embedding(vector)
                        .metadata(upsertMeta)
                        .build()));
            } catch (Exception e) {
                log.warn("SemanticMemoryService.ingest: vectorStoreAdapter.upsert failed, embedding not persisted: {}", e.getMessage());
            }
        }

        invalidateCache(tenantId);
        log.info("SemanticMemoryService.ingest: id={}, tenantId={}, contentLength={}", fragment.getId(), tenantId, content.length());
        return fragment;
    }

    /**
     * 按语义相似度检索语义记忆片段。
     * <p>
     * 将查询文本通过 {@link EmbeddingAdapter} 转为向量，经 {@link VectorStoreAdapter}
     * 执行 pgvector 余弦距离检索（<=> ），返回最相似的 topK 条片段。
     * 当 topK 小于等于 {@value #CACHE_THRESHOLD} 时优先从 Redis 缓存读取。
     * 若向量检索失败（如嵌入服务不可用），回退到按创建时间倒序查询。
     *
     * @param tenantId 租户 ID
     * @param query    查询文本，用于生成查询向量
     * @param topK     返回结果数量上限
     * @return 语义记忆片段列表，按相似度从高到低排列
     * @since 1.0.0
     */
    @SuppressWarnings("unchecked")
    public List<SemanticFragment> search(String tenantId, String query, int topK) {
        String cacheKey = "wenshi:semantic:" + tenantId + ":" + query.hashCode() + ":" + topK;
        if (topK <= CACHE_THRESHOLD) {
            List<SemanticFragment> cached = cacheService.get(cacheKey, List.class);
            if (cached != null) {
                log.debug("SemanticMemoryService.search: cache hit, tenantId={}", tenantId);
                return cached;
            }
        }

        List<SemanticFragment> result;
        if (vectorStoreAdapter != null) {
            try {
                // 向量语义检索
                float[] queryVector = embeddingAdapter.embed(query);
                Map<String, Object> filters = new HashMap<>();
                filters.put("tenantId", tenantId);
                List<VectorFragment> fragments = vectorStoreAdapter.search(queryVector, topK, filters);
                result = new ArrayList<>(fragments.size());
                for (VectorFragment vf : fragments) {
                    SemanticFragment sf = new SemanticFragment();
                    sf.setId(vf.getId());
                    sf.setContent(vf.getContent());
                    sf.setTenantId(tenantId);
                    result.add(sf);
                }
                log.debug("SemanticMemoryService.search: vector search returned {} results", result.size());
            } catch (Exception e) {
                // 向量检索失败时回退到按创建时间排序
                log.warn("SemanticMemoryService.search: vector search failed, falling back to time-based query: {}", e.getMessage());
                result = mapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SemanticFragment>()
                                .eq(SemanticFragment::getTenantId, tenantId)
                                .orderByDesc(SemanticFragment::getCreatedAt)
                                .last("LIMIT " + topK)
                );
            }
        } else {
            // wenshi 数据源不可用，直接使用时间排序
            result = mapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SemanticFragment>()
                            .eq(SemanticFragment::getTenantId, tenantId)
                            .orderByDesc(SemanticFragment::getCreatedAt)
                            .last("LIMIT " + topK)
            );
        }

        if (topK <= CACHE_THRESHOLD) {
            cacheService.set(cacheKey, result, CACHE_TTL);
        }
        return result;
    }

    /**
     * 删除指定的语义记忆片段。
     * <p>
     * 删除后自动失效该租户下所有语义检索缓存。
     *
     * @param tenantId    租户 ID
     * @param fragmentId  待删除的语义片段 ID
     * @since 1.0.0
     */
    public void delete(String tenantId, String fragmentId) {
        mapper.deleteById(fragmentId);
        invalidateCache(tenantId);
        log.info("SemanticMemoryService.delete: id={}", fragmentId);
    }

    /**
     * 失效指定租户下所有语义检索缓存。
     * <p>
     * 使用模式匹配删除所有以 {@code wenshi:semantic:{tenantId}:} 开头的缓存 key。
     *
     * @param tenantId 租户 ID
     */
    private void invalidateCache(String tenantId) {
        cacheService.deleteByPattern("wenshi:semantic:" + tenantId + ":*");
    }
}
