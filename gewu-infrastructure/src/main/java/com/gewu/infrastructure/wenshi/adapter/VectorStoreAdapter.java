package com.gewu.infrastructure.wenshi.adapter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 向量存储适配器接口 — 提供向量片段的增删查能力。
 * <p>
 * 底层实现可以是 pgvector、Milvus、Qdrant 等向量数据库。
 * 实现类需保证 upsert 和 delete 操作的幂等性。
 *
 * @since 1.0.0
 */
public interface VectorStoreAdapter {

    /**
     * 批量插入或更新向量片段。
     * <p>
     * 若片段 ID 已存在则更新内容和向量，否则插入新记录。
     *
     * @param fragments 待写入的向量片段列表，不可为 null
     */
    void upsert(List<VectorFragment> fragments);

    /**
     * 按向量相似度检索最近的片段。
     *
     * @param query  查询向量，不可为 null
     * @param topK   返回结果数量上限
     * @param filters 过滤条件键值对，可为 null 表示不过滤
     * @return 按相似度降序排列的片段列表
     */
    List<VectorFragment> search(float[] query, int topK, Map<String, Object> filters);

    /**
     * 批量删除指定 ID 的向量片段。
     *
     * @param ids 待删除的片段 ID 列表，不可为 null
     */
    void delete(List<String> ids);

    /**
     * 向量片段数据模型 — 表示一条带元数据的文本向量记录。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class VectorFragment {

        /** 片段唯一标识 */
        private String id;

        /** 原始文本内容 */
        private String content;

        /** 文本对应的嵌入向量 */
        private float[] embedding;

        /** 业务元数据，如 tenantId、source、userId 等 */
        private Map<String, Object> metadata;
    }
}
