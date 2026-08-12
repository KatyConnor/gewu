package com.gewu.domain.workspace;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workspace_project")
public class WorkspaceProject extends BaseEntity {

    private String workspaceId;
    private String projectName;
    private String repoUrl;
    private String repoBranch;
    /** 容器内相对路径 (如 projects/my-service) */
    private String localPath;
    /** pending / cloning / ready / failed */
    private String cloneStatus;
    private Long lastSyncAt;
    private String headCommit;
}
