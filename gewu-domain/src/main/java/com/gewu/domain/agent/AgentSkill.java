package com.gewu.domain.agent;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 智能体技能关联实体.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_skill")
public class AgentSkill extends BaseEntity {

    private String agentId;
    private String skillId;
    private Integer sortOrder;
}
