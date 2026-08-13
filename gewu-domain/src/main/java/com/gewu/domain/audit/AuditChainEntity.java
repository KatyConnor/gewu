package com.gewu.domain.audit;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * WORM 审计链记录 - 链式哈希保证不可篡改。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("audit_chain")
public class AuditChainEntity extends BaseEntity {

    /** 事件类型: AGENT_EXECUTE/ORCHESTRATION/GOAL/HITL/CONFLICT */
    private String eventType;
    /** 执行实例 ID */
    private String executionId;
    /** 执行者: user/agent/system */
    private String actor;
    /** 动作: create/execute/approve/reject/cancel */
    private String action;
    /** 决策轨迹 JSON */
    private String decisionTrace;
    /** 多Agent通信记录 JSON */
    private String agentCollaborationLog;
    /** 前一条记录哈希（链式哈希） */
    private String hashPrevious;
    /** 当前记录哈希 */
    private String hashCurrent;
    /** 是否已校验 */
    private Integer verified;
    /** 创建时间戳 */
    private Long createdAt;
}