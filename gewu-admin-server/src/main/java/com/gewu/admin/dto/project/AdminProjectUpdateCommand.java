package com.gewu.admin.dto.project;

import lombok.Data;

import java.util.List;

/**
 * 管理端更新项目命令（全字段可选：null=不修改）.
 *
 * <p>团队语义：techLeadUserId 为 null 不变、空串清除负责人、非空替换（并同步 ownerId）；
 * memberUserIds 为 null 不变、空列表清空成员、非空全量替换。
 */
@Data
public class AdminProjectUpdateCommand {

    private String projectName;

    private String shortName;

    private String systemName;

    private String description;

    private Integer visibility;

    /** 1=进行中 2=待立项 3=已上线 4=已暂停 5=已取消 */
    private Integer status;

    private String techStack;

    private String vcs;

    private String worktree;

    private String iconUrl;

    private String iconColor;

    private String repoUrl;
    /** 克隆分支（null=不修改；空白=清除并回落仓库远程默认分支） */
    private String repoBranch;
    /** 托管平台类型（github/gitlab/gitee/gitea；空=按域名推断） */
    private String platformType;
    /** 自建实例 REST API 根地址（空=按平台默认推导） */
    private String apiBase;

    private String repoCredentialType;

    private String repoUsername;

    /** 非空白时重新加密落库 */
    private String repoCredential;

    private String techLeadUserId;

    private List<String> memberUserIds;
}
