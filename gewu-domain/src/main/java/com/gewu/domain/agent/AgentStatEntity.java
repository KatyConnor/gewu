package com.gewu.domain.agent;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 执行统计 - 渐进式权限模型的信任等级数据基础。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_stat")
public class AgentStatEntity extends BaseEntity {

    /** Agent ID */
    private String agentId;
    /** 总任务数 */
    private Integer totalTasks;
    /** 成功任务数 */
    private Integer successCount;
    /** 成功率 (0-100) */
    private Double successRate;
    /** 信任等级: L0/L1/L2/L3 */
    private String trustLevel;
    /** 最后执行时间 */
    private Long lastExecutedAt;
}