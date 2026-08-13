package com.gewu.domain.orchestration;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 失败案例 - 结构化记录任务失败的经验，供后续任务检索复用与规避。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("failure_case")
public class FailureCaseEntity extends BaseEntity {

    /** 任务类型 */
    private String taskType;
    /** 任务上下文摘要 */
    private String contextSummary;
    /** 失败步骤 */
    private String failurePoint;
    /** 归因分析 */
    private String rootCause;
    /** 经验教训 */
    private String lesson;
    /** 规避规则（可编码为路由规则） */
    private String avoidanceRule;
    /** 置信度 */
    private Float confidence;
    /** 出现次数 */
    private Integer occurrenceCount;
    /** 关联 Agent ID */
    private String agentId;
    /** 关联执行实例 ID */
    private String executionId;
}