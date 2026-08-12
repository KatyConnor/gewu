package com.gewu.domain.workspace;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workspace_file")
public class WorkspaceFile extends BaseEntity {

    private String workspaceId;
    private String parentId;
    private String fileName;
    /** 1=目录 2=文件 */
    private Integer fileType;
    private String filePath;
    private String objectKey;
    private String mimeType;
    private Long fileSize;
    private String checksum;
    private Integer version;
    /** 1=正常 2=回收站 */
    private Integer status;
}
