package com.gewu.application.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateProjectCommand {

    @NotBlank(message = "项目名称不能为空")
    @Size(max = 128, message = "项目名称最长 128 个字符")
    private String projectName;

    @Size(max = 64, message = "项目简称最长 64 个字符")
    private String shortName;

    @Size(max = 128, message = "所属系统最长 128 个字符")
    private String systemName;

    @Size(max = 512, message = "团队成员最长 512 个字符")
    private String teamMembers;

    @Size(max = 64, message = "技术负责人最长 64 个字符")
    private String techLead;

    @Size(max = 1024, message = "项目描述最长 1024 个字符")
    private String description;

    private Integer visibility;

    private String techStack;

    private String worktree;

    private String vcs;

    private String iconUrl;

    private String iconColor;

    /** Git 仓库地址 */
    @Size(max = 512, message = "仓库地址最长 512 个字符")
    private String repoUrl;

    /** 仓库认证方式与凭证、用户名密码二选一：token/ssh_key 时配套 repoCredential */
    private String repoCredentialType;

    /** 仓库用户名（repoCredentialType=password 时配套） */
    @Size(max = 128, message = "仓库用户名最长 128 个字符")
    private String repoUsername;

    /** 仓库访问凭证明文（访问令牌 / SSH 私钥 / 密码），入库前 SM4 加密 */
    private String repoCredential;
}
