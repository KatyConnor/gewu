package com.gewu.application.project.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateProjectCommand {

    @Size(max = 128, message = "项目名称最长 128 个字符")
    private String projectName;

    @Size(max = 1024, message = "项目描述最长 1024 个字符")
    private String description;

    private Integer visibility;

    private String techStack;

    private String worktree;

    private String vcs;

    private String iconUrl;

    private String iconColor;
}