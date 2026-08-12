package com.gewu.application.project.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DocumentVersionDTO {

    private String id;
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
