package com.gewu.domain.project;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("phase_document_version")
public class PhaseDocumentVersion extends BaseEntity {

    private String documentId;
    private Integer versionNo;
    private String fileUrl;
    private String contentMd5;
    private String changeSummary;
    private String changeSource;
    private String agentId;
    private String agentPrompt;
    private Long fileSize;
    private String uploadedBy;
    private Long uploadedAt;
}
