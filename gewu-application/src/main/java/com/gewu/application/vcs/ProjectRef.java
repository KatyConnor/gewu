package com.gewu.application.vcs;

import lombok.Data;

/**
 * 托管平台访问上下文（从项目配置 repoUrl + token 凭证解析）。
 */
@Data
public class ProjectRef {
    private PlatformType platform;
    /** REST API 根地址（如 https://api.github.com、自建 https://{host}/api/v4） */
    private String apiBase;
    /** 平台网页根地址（拼接跳转链接用） */
    private String webBase;
    /** 仓库路径：owner/repo（GitLab 含 subgroup 全路径） */
    private String repoPath;
    /** 访问令牌（项目配置的 SM4 凭证解密后） */
    private String token;
}
