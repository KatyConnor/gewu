package com.gewu.domain.agent;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseSimpleEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_permission")
public class AgentPermission extends BaseSimpleEntity {

    private String agentId;
    private String permissionCode;
    private String effect;
    private String conditionExpr;
    private Integer priority;
    private String createdBy;
}