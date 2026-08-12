package com.gewu.domain.workspace;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workspace")
public class Workspace extends BaseEntity {

    private String userId;
    private String workspaceName;
    private String storagePath;
    private Long quotaBytes;
    private Long usedBytes;
    private Integer fileCount;
    private Integer status;
    /** 存储模式: storage=MinIO, dev=Docker卷 */
    private String mode;
    /** 开发沙箱ID (mode=dev时关联) */
    private String devSandboxId;
}
