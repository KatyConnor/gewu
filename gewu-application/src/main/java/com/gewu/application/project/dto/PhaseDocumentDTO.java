package com.gewu.application.project.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PhaseDocumentDTO {

    private String id;
    private String projectId;
    private String phaseCode;
    private String docName;
    private String docType;
    private Integer currentVersion;
    private Integer totalVersions;
    private String reviewStatus;
    private String reviewStatusDesc;
    private String reviewedBy;
    private Long reviewedAt;
    private String reviewComment;
    private Long createdAt;
    private String createdBy;
}
