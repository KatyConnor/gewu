package com.gewu.domain.project;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project")
public class Project extends BaseEntity {

    private String projectName;
    private String description;
    private Integer visibility;
    private Integer status;
    private String ownerId;
    private String techStack;
    private String worktree;
    private String vcs;
    private String iconUrl;
    private String iconColor;
    private Long timeInitialized;
    private String sandboxes;
    private String commands;
}