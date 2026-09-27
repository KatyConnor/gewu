package com.veloflow.engine.persistence.model;

import com.baomidou.mybatisplus.annotation.TableName;
import com.veloflow.engine.commons.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("VLF_WORKFLOW")
public class Workflow extends BaseEntity {

    private String workflowName;
    private String description;
    private Integer version;
    private Integer status;
    private String category;
    private String config;
    private Long publishedAt;
}