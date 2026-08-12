package com.gewu.domain.wenshi.knowledge;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wenshi_semantic_fragment")
public class SemanticFragment extends BaseEntity {

    private String tenantId;
    private String ownerUserId;
    private String graphNodeId;
    private String content;
    private String source;
    private java.math.BigDecimal confidence;
    private String metadata;

    /**
     * 向量嵌入（pgvector vector(384)），通过 PgvectorAdapter 原生 SQL 读写，
     * 不参与 MyBatis-Plus 自动映射。
     */
    @TableField(exist = false)
    private float[] embedding;
}
