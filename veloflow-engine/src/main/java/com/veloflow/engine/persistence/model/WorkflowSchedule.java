package com.veloflow.engine.persistence.model;

import com.baomidou.mybatisplus.annotation.TableName;
import com.veloflow.engine.commons.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Veloflow 定时触发配置（每流程一条） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("VLF_WORKFLOW_SCHEDULE")
public class WorkflowSchedule extends BaseEntity {

    private String workflowId;
    private String cronExpr;
    private String timezone;
    private String inputTemplate;
    private Integer enabled;
    private Long lastFireAt;
    private Long nextFireAt;
}
