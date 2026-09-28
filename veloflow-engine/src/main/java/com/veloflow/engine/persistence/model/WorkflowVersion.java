package com.veloflow.engine.persistence.model;

import com.baomidou.mybatisplus.annotation.TableName;
import com.veloflow.engine.commons.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Veloflow 定义版本快照（51 号 T4.4）：发布时聚合 nodes/transitions 不可变存档 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("VLF_WORKFLOW_VERSION")
public class WorkflowVersion extends BaseEntity {

    private String workflowId;
    /** 版本号（同流程内自增，发布一次 +1） */
    private Integer version;
    /** nodes+transitions+config 不可变快照（JSON） */
    private String definitionSnapshot;
    private String publishedBy;
    private Long publishedAt;
}
