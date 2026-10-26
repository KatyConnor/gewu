package com.gewu.application.vcs.dto;

import lombok.Data;

/**
 * 合并请求（PR/MR）统一模型（跨平台归一）。
 */
@Data
public class MergeRequestDTO {
    /** 平台编号（GitHub PR 号 / GitLab iid） */
    private long number;
    private String title;
    private String description;
    /** 归一状态：open / merged / closed */
    private String state;
    private String sourceBranch;
    private String targetBranch;
    private String webUrl;
    private String author;
    private Long createdAt;
    /** 源分支 HEAD SHA（CI 查询用，详情接口返回） */
    private String headSha;
}
