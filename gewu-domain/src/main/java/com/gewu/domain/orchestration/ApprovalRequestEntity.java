package com.gewu.domain.orchestration;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * HITL 审批请求 - 持久化人工介入审批流程。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("approval_request")
public class ApprovalRequestEntity extends BaseEntity {

    /** 编排执行实例 ID */
    private String executionId;
    /** 触发审批的节点 ID */
    private String nodeId;
    /** 审批类型: MANUAL_REVIEW/TAKEOVER/ROLLBACK */
    private String approvalType;
    /** 审批内容 JSON */
    private String payload;
    /** 指定审批人（用户 ID，空=全员可见，WFO-07） */
    private String assigneeId;
    /** 指定审批角色（角色编码，与 assigneeId 并用） */
    private String assigneeRole;
    /** 状态: pending/approved/rejected/timeout */
    private String status;
    /** 审批人 */
    private String approver;
    /** 审批意见 */
    private String approvalComment;
    /** 审批时间 */
    private Long approvedAt;
    /** 超时时间 */
    private Long timeoutAt;
}
