package com.gewu.domain.orchestration;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 编排执行实例 - 持久化编排引擎的执行状态与结果。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("orchestration_execution")
public class OrchestrationExecutionEntity extends BaseEntity {

    /** 编排图 ID */
    private String graphId;
    /** 执行时图快照 JSON */
    private String graphSnapshot;
    /** 执行绑定的图版本 ID（无版本快照的存量执行为 NULL） */
    private String versionId;
    /** 发起用户 ID */
    private String userId;
    /** 会话 ID */
    private String sessionId;
    /** 触发方式: MANUAL/API/AGENT_TOOL/SCHEDULE/WEBHOOK/WORKFLOW_CALL */
    private String triggerType;
    /** 状态: PENDING/RUNNING/PAUSED/SUCCEEDED/FAILED/CANCELLED */
    private String status;
    /** 迭代次数 */
    private Integer iterationCount;
    /** 当前执行节点 ID */
    private String currentNodeId;
    /** 执行变量 JSON */
    private String variables;
    /** 最终输出 */
    private String finalOutput;
    /** 错误信息 */
    private String errorMessage;
    /** Token 消耗 */
    private Long tokenUsed;
    /** 成本消耗（元） */
    private BigDecimal costConsumed;
    /** 任务等级 L1/L2/L3 */
    private String taskLevel;
    /** 开始时间（毫秒） */
    private Long startedAt;
    /** 完成时间（毫秒） */
    private Long completedAt;
}
