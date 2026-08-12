package com.gewu.application.wenshi.knowledge;

import com.gewu.domain.wenshi.knowledge.SemanticFragment;
import com.gewu.infrastructure.cache.CacheService;
import com.gewu.infrastructure.mapper.wenshi.SemanticFragmentMapper;
import com.gewu.infrastructure.wenshi.adapter.EmbeddingAdapter;
import com.gewu.infrastructure.wenshi.adapter.VectorStoreAdapter;
import com.gewu.infrastructure.wenshi.adapter.VectorStoreAdapter.VectorFragment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SemanticMemoryService 单元测试。
 * <p>
 * 验证 P0-3 修复：ingest() 不再丢弃向量、search() 使用向量检索而非时间排序。
 */
@ExtendWith(MockitoExtension.class)
class SemanticMemoryServiceTest {

    @Mock private SemanticFragmentMapper mapper;
    @Mock private EmbeddingAdapter embeddingAdapter;
    @Mock private CacheService cacheService;
    @Mock private VectorStoreAdapter vectorStoreAdapter;

    @InjectMocks private SemanticMemoryService service;

    @Test
    void ingest_shouldComputeEmbeddingAndCallUpsert() {
        // Arrange
        float[] vector = new float[]{0.1f, 0.2f, 0.3f};
        when(embeddingAdapter.embed("test content")).thenReturn(vector);

        // Act
        SemanticFragment result = service.ingest("tenant-1", "user-1", "test content", "MANUAL", null);

        // Assert
        assertNotNull(result.getId());
        assertEquals("tenant-1", result.getTenantId());
        assertEquals("test content", result.getContent());
        assertArrayEquals(vector, result.getEmbedding());

        // 验证 embed 被调用（向量不再被丢弃）
        verify(embeddingAdapter).embed("test content");
        // 验证 mapper.insert 写入基础字段
        verify(mapper).insert(any(SemanticFragment.class));
        // 验证 vectorStoreAdapter.upsert 写入向量
        verify(vectorStoreAdapter).upsert(argThat(fragments -> {
            VectorFragment vf = fragments.get(0);
            return vf.getId().equals(result.getId())
                    && vf.getContent().equals("test content")
                    && vf.getEmbedding() == vector;
        }));
    }

    @Test
    void ingest_upsertFailureShouldNotFailIngest() {
        // Arrange
        when(embeddingAdapter.embed(anyString())).thenReturn(new float[]{0.1f});
        doThrow(new RuntimeException("pgvector unavailable"))
                .when(vectorStoreAdapter).upsert(anyList());

        // Act - 不应抛出异常
        SemanticFragment result = service.ingest("t1", "u1", "content", null, null);

        // Assert - 基础字段仍写入成功
        assertNotNull(result.getId());
        verify(mapper).insert(any(SemanticFragment.class));
    }

    @Test
    void search_shouldUseVectorSearchNotTimeOrder() {
        // Arrange - topK=3 触发缓存检查（返回 null），再走向量检索
        float[] queryVector = new float[]{0.5f, 0.5f};
        when(embeddingAdapter.embed("query text")).thenReturn(queryVector);
        when(cacheService.get(anyString(), eq(List.class))).thenReturn(null);

        VectorFragment vf1 = VectorFragment.builder().id("frag-1").content("result 1").build();
        VectorFragment vf2 = VectorFragment.builder().id("frag-2").content("result 2").build();
        when(vectorStoreAdapter.search(eq(queryVector), eq(3), anyMap()))
                .thenReturn(List.of(vf1, vf2));

        // Act
        List<SemanticFragment> results = service.search("tenant-1", "query text", 3);

        // Assert
        assertEquals(2, results.size());
        assertEquals("frag-1", results.get(0).getId());
        assertEquals("result 1", results.get(0).getContent());
        assertEquals("tenant-1", results.get(0).getTenantId());

        // 验证使用了向量检索，而非 mapper.selectList（时间排序）
        verify(embeddingAdapter).embed("query text");
        verify(vectorStoreAdapter).search(eq(queryVector), eq(3), argThat(m -> "tenant-1".equals(m.get("tenantId"))));
        verify(mapper, never()).selectList(any());
    }

    @Test
    void search_shouldFallbackToTimeBasedOnVectorFailure() {
        // Arrange
        when(embeddingAdapter.embed(anyString())).thenThrow(new RuntimeException("embedding service down"));
        when(cacheService.get(anyString(), eq(List.class))).thenReturn(null);
        SemanticFragment fallback = new SemanticFragment();
        fallback.setId("fb-1");
        fallback.setContent("fallback result");
        when(mapper.selectList(any())).thenReturn(List.of(fallback));

        // Act
        List<SemanticFragment> results = service.search("t1", "query", 3);

        // Assert - 回退到时间排序
        assertEquals(1, results.size());
        assertEquals("fb-1", results.get(0).getId());
        verify(mapper).selectList(any());
    }

    @Test
    void search_shouldReturnCachedResultWhenAvailable() {
        // Arrange
        SemanticFragment cached = new SemanticFragment();
        cached.setId("cached-1");
        cached.setContent("cached content");
        when(cacheService.get(anyString(), eq(List.class))).thenReturn(List.of(cached));

        // Act
        List<SemanticFragment> results = service.search("t1", "query", 3);

        // Assert
        assertEquals(1, results.size());
        assertEquals("cached-1", results.get(0).getId());
        // 不应调用向量检索
        verify(embeddingAdapter, never()).embed(anyString());
        verify(vectorStoreAdapter, never()).search(any(), anyInt(), anyMap());
    }
}
