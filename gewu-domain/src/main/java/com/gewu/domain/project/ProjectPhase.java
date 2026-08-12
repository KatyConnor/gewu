package com.gewu.domain.project;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project_phase")
public class ProjectPhase extends BaseEntity {

    private String projectId;
    private String phaseCode;
    private Integer phaseOrder;
    private Integer status;
    private Long startedAt;
    private Long completedAt;
    private String agentId;
}
