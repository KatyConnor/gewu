package com.gewu.application.workspace.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 工作空间文件树节点 DTO.
 */
@Data
@Builder
public class FileNodeDTO {

    private String fileId;
    private String parentId;
    private String fileName;
    /** 1=目录 2=文件 */
    private Integer fileType;
    private String filePath;
    private Long fileSize;
    private String mimeType;
    private Integer version;
    /** 子节点数（目录类型才有意义） */
    private Integer childrenCount;
    private Long createdAt;
    private Long updatedAt;
}
