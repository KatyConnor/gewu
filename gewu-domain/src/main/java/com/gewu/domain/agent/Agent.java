package com.gewu.domain.agent;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent")
public class Agent extends BaseEntity {

    private String agentName;
    private String description;
    private String modelProvider;
    private String modelName;
    private String modelConfig;
    private String systemPrompt;
    private Integer status;
    private Integer version;
}