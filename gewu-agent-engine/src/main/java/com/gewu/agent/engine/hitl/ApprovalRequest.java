package com.gewu.agent.engine.hitl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 人机协同审批请求。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalRequest {
    /** 审批 ID */
    private String approvalId;
    /** 执行实例 ID */
    private String executionId;
    /** 节点 ID */
    private String nodeId;
    /** 审批类型：APPROVE_REJECT / INPUT / SELECT / EDIT */
    private String type;
    /** Agent 产出摘要 */
    private String summary;
    /** 待审产物（代码 / 文档 / 部署计划） */
    private Object artifact;
    /** 选项列表（SELECT 类型） */
    private java.util.List<String> options;
    /** 超时（秒） */
    private Integer timeoutSeconds;
}