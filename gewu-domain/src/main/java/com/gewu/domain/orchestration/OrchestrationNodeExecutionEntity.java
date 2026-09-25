package com.gewu.domain.orchestration;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 编排节点执行记录 - 持久化编排引擎中每个节点的执行详情。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("orchestration_node_execution")
public class OrchestrationNodeExecutionEntity extends BaseEntity {

    /** 编排执行实例 ID */
    private String executionId;
    /** 节点 ID */
    private String nodeId;
    /** 节点类型: AGENT/TOOL/HUMAN/ROUTER/PARALLEL/MERGE/SUBGRAPH */
    private String nodeType;
    /** 角色编码（AGENT 节点） */
    private String roleCode;
    /** 状态: PENDING/RUNNING/COMPLETED/FAILED/SKIPPED */
    private String status;
    /** 节点输入 JSON */
    private String input;
    /** 节点输出 JSON */
    private String output;
    /** Token 消耗 */
    private Integer tokenUsed;
    /** 节点重试次数（成功前的额外尝试次数，WFO-05） */
    private Integer retryCount;
    /** 执行时长（毫秒） */
    private Long durationMs;
    /** 错误信息 */
    private String errorMessage;
    /** 开始时间 */
    private Long startedAt;
    /** 完成时间 */
    private Long completedAt;
}
