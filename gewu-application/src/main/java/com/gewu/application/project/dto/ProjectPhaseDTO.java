package com.gewu.application.project.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProjectPhaseDTO {

    private String id;
    private String projectId;
    private String phaseCode;
    private String phaseName;
    private Integer phaseOrder;
    private Integer status;
    private String statusDesc;
    private Long startedAt;
    private Long completedAt;
    private String agentId;
    private boolean revertible;
    private int documentCount;
}
