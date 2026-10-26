package com.gewu.domain.workspace;

import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工作空间双向同步状态（设计文档 42 项三）。
 * <p>同步范围限定文档类文件：项目代码目录（projects/**）不入 MinIO，
 * 代码由 Git 仓库 push/pull 管理。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WorkspaceSyncState extends BaseEntity {

    public static final int DIRECTION_V2M = 1;
    public static final int DIRECTION_M2V = 2;
    public static final int DIRECTION_DEL_V = 3;
    public static final int DIRECTION_DEL_M = 4;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_DONE = 1;
    public static final int STATUS_CONFLICT = 2;
    public static final int STATUS_FAILED = 3;

    /** 工作空间 ID */
    private String workspaceId;
    /** 卷内相对路径（= workspace_file.file_path） */
    private String filePath;
    /** SHA-256(workspaceId|filePath)，唯一键用（路径列超长不能直接入唯一键） */
    private String filePathHash;
    /** 同步方向：1=V2M 2=M2V 3=DEL_V 4=DEL_M */
    private Integer direction;
    /** 入队时卷侧 mtime（秒），冲突判定基准 */
    private Long sourceMtime;
    /** 入队时源侧大小（字节） */
    private Long sourceSize;
    /** 状态：0=待办 1=成功 2=冲突 3=失败 */
    private Integer status;
    /** 失败原因 */
    private String failReason;
}
