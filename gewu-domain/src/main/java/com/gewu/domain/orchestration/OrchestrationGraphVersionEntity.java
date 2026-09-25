package com.gewu.domain.orchestration;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 编排图版本快照 - 激活时产生的不可变定义版本（WFO-01）。
 * <p>执行记录通过 versionId 绑定版本，回放不受后续再激活影响；
 * 版本快照一旦写入不更新不覆盖（uk: graph_id + version）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("orchestration_graph_version")
public class OrchestrationGraphVersionEntity extends BaseEntity {

    /** 编排图 ID */
    private String graphId;
    /** 版本号（同图内自增，从 1 起） */
    private Integer version;
    /** 不可变版本快照 JSON */
    private String graphDefinition;
    /** 快照时的编排模式 */
    private String orchestrationMode;
    /** 激活操作人 */
    private String activatedBy;
    /** 激活时间（毫秒） */
    private Long activatedAt;
}
