package com.gewu.domain.wenshi.learning;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wenshi_experience")
public class Experience extends BaseEntity {

    private String tenantId;
    private String scenarioHash;
    private String scenario;
    private String strategy;
    private String outcome;
    private java.math.BigDecimal score;
    private String lesson;
    private String sourceTask;
    private Integer hitCount;

    /**
     * 向量嵌入（pgvector vector(384)），通过自定义 SQL 读写，不参与 MyBatis-Plus 自动映射。
     */
    @TableField(exist = false)
    private float[] embedding;
}
