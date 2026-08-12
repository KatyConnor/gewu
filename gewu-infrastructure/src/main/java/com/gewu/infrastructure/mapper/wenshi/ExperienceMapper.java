package com.gewu.infrastructure.mapper.wenshi;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.wenshi.learning.Experience;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ExperienceMapper extends BaseMapper<Experience> {

    /**
     * 按向量相似度检索经验（pgvector 余弦距离 <=> ）。
     * <p>
     * 向量参数以 pgvector 字面量格式 "[0.1,0.2,...]" 传入，CAST 为 vector 类型。
     * 仅检索未删除且 embedding 非空的经验。
     *
     * @param queryVector 查询向量的 pgvector 字面量字符串
     * @param tenantId    租户 ID
     * @param topK        返回数量上限
     * @return 按相似度降序排列的经验列表
     */
    @Select("SELECT id, tenant_id, scenario_hash, scenario, strategy, outcome, score, lesson, " +
            "source_task, hit_count, created_at, updated_at, created_by, updated_by, deleted " +
            "FROM wenshi_experience " +
            "WHERE tenant_id = #{tenantId} AND deleted = 0 AND embedding IS NOT NULL " +
            "ORDER BY embedding <=> CAST(#{queryVector} AS vector) " +
            "LIMIT #{topK}")
    List<Experience> searchByVector(@Param("queryVector") String queryVector,
                                    @Param("tenantId") String tenantId,
                                    @Param("topK") int topK);

    /**
     * 增加经验的命中计数。
     *
     * @param id 经验 ID
     */
    @Update("UPDATE wenshi_experience SET hit_count = COALESCE(hit_count, 0) + 1 WHERE id = #{id}")
    void incrementHitCount(@Param("id") String id);

    /**
     * 更新经验的向量嵌入（pgvector），用于后续语义检索。
     *
     * @param id        经验 ID
     * @param embedding pgvector 字面量字符串 "[0.1,0.2,...]"
     */
    @Update("UPDATE wenshi_experience SET embedding = CAST(#{embedding} AS vector) WHERE id = #{id}")
    void updateEmbedding(@Param("id") String id, @Param("embedding") String embedding);
}
