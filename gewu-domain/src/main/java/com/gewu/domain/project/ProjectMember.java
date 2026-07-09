package com.gewu.domain.project;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project_member")
public class ProjectMember extends BaseEntity {

    private String projectId;
    private String userId;
    private String roleCode;
    private Long joinedAt;
}