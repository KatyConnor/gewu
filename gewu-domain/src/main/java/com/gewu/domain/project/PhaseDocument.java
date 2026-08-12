package com.gewu.domain.project;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("phase_document")
public class PhaseDocument extends BaseEntity {

    private String projectId;
    private String phaseCode;
    private String docName;
    private String docType;
    private Integer currentVersion;
    private Integer totalVersions;
    private String reviewStatus;
    private String reviewedBy;
    private Long reviewedAt;
    private String reviewComment;
}
