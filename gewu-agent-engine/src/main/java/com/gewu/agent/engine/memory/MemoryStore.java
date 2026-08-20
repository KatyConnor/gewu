package com.gewu.agent.engine.memory;

import java.util.List;

/**
 * 记忆存储 SPI - 记忆片段的检索与持久化。
 * <p>使用方实现此接口对接向量数据库（pgvector / Milvus / Weaviate 等）。
 * 框架提供 {@code NoOpMemoryStore}（返回空），此时无记忆能力。
 * <p>四类记忆映射：
 * <ul>
 *   <li>语义记忆 - 领域知识 / 规范 / 代码模式</li>
 *   <li>情景记忆 - 历史执行事件 / 协作经历</li>
 *   <li>程序性记忆 - 技能库（演化产物）</li>
 *   <li>参数化记忆 - 用户偏好 / 项目配置</li>
 * </ul>
 *
 * @since 1.0.0
 */
public interface MemoryStore {

    /** 存储记忆片段 */
    void store(MemoryFragment fragment);

    /** 语义检索：按 query 文本检索最相似的 topK 个片段 */
    List<MemoryFragment> retrieve(String domain, String query, int topK);

    /** 按 metadata 过滤检索 */
    default List<MemoryFragment> retrieveByMetadata(String domain, java.util.Map<String, Object> filter) {
        return java.util.List.of();
    }

    /** 删除指定域的记忆 */
    default void clear(String domain) {
    }
}