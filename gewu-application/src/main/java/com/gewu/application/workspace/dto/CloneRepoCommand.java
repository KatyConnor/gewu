package com.gewu.application.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 克隆 Git 仓库命令.
 */
@Data
public class CloneRepoCommand {

    @NotBlank(message = "仓库地址不能为空")
    @Size(max = 512, message = "仓库地址最长 512 字符")
    private String repoUrl;

    @Size(max = 128, message = "分支名最长 128 字符")
    private String repoBranch;

    @NotBlank(message = "项目名称不能为空")
    @Size(max = 128, message = "项目名称最长 128 字符")
    private String projectName;
}
