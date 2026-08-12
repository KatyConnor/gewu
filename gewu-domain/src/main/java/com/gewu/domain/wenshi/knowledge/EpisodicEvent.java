package com.gewu.domain.wenshi.knowledge;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wenshi_episodic_event")
public class EpisodicEvent extends BaseEntity {

    private String tenantId;
    private String userId;
    private String sessionId;
    private String eventType;
    private String content;
    private String metadata;
}
