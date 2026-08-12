package com.gewu.domain.wenshi.learning;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wenshi_reasoning_trace")
public class ReasoningTrace extends BaseEntity {

    private String tenantId;
    private String sessionId;
    private String taskId;
    private String planTree;
    private String traceSteps;
    private String usedKnowledge;
    private String reusedExperienceId;
    private String tokenStats;
    private Long reasoningMs;
    private Boolean fromExperience;
}
