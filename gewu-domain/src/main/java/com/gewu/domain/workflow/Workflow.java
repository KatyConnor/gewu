package com.gewu.domain.workflow;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow")
public class Workflow extends BaseEntity {

    private String workflowName;
    private String description;
    private Integer version;
    private Integer status;
    private String category;
    private String config;
    private Long publishedAt;
}