package com.gewu.domain.project;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

@Data
@TableName("project_directory")
public class ProjectDirectory implements Serializable {

    private String projectId;
    private String directory;
    private String type;
    private String strategy;
    private Long timeCreated;
}