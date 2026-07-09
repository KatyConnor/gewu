package com.gewu.application.project.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProjectDTO {

    private String projectId;
    private String projectName;
    private String description;
    private Integer visibility;
    private Integer status;
    private String ownerId;
    private String ownerName;
    private Long memberCount;
    private Long createdAt;
    private String techStack;
    private String worktree;
    private String iconUrl;
    private String iconColor;
}