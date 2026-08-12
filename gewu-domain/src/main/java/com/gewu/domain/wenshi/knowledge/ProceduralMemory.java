package com.gewu.domain.wenshi.knowledge;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wenshi_procedural_memory")
public class ProceduralMemory extends BaseEntity {

    private String tenantId;
    private String type;
    private String name;
    private String description;
    private String definition;
    private Integer usageCount;
    private java.math.BigDecimal successRate;
    private Integer skillLevel;
    private String learnedFrom;
    private Integer status;
}
