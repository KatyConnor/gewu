package com.gewu.application.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 添加 Git 凭证命令.
 */
@Data
public class AddGitCredentialCommand {

    @NotBlank(message = "凭证名称不能为空")
    @Size(max = 64, message = "凭证名称最长 64 字符")
    private String credName;

    @NotBlank(message = "凭证类型不能为空")
    private String credType; // ssh_key / token

    @NotBlank(message = "凭证内容不能为空")
    private String credValue;

    private String gitHost;
}
