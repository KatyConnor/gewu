package com.gewu.domain.orchestration;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 编排图定义 - 持久化编排引擎的 DAG 定义。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("orchestration_graph")
public class OrchestrationGraphEntity extends BaseEntity {

    /** 编排图名称 */
    private String graphName;
    /** DAG JSON（nodes + edges + variables） */
    private String graphDefinition;
    /** 状态 Schema JSON */
    private String stateSchema;
    /** 图类型: SDLC_PIPELINE/GOAL_DECOMPOSED/AD_HOC/TEMPLATE */
    private String graphType;
    /** 编排模式: SUPERVISOR/PIPELINE/SWARM/DEBATE */
    private String orchestrationMode;
    /** 版本号 */
    private String version;
    /** 状态: draft/active/archived */
    private String status;
    /** 关联自主目标 ID */
    private String rootGoalId;
}
