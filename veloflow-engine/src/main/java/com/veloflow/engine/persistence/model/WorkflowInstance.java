package com.veloflow.engine.persistence.model;

import com.baomidou.mybatisplus.annotation.TableName;
import com.veloflow.engine.commons.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("VLF_WORKFLOW_INSTANCE")
public class WorkflowInstance extends BaseEntity {

    private String workflowId;
    private Integer workflowVersion;
    private String title;
    private String status;
    private String initiatorId;
    /** 触发方式: MANUAL/SCHEDULE/WEBHOOK/EVENT/UPSTREAM */
    private String triggerType;
    /** 终态输出（return 节点产出） */
    private String finalOutput;
    /** 失败原因 */
    private String errorMessage;
    private String currentNodeId;
    private String variables;
    private Long startedAt;
    private Long completedAt;
}