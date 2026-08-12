package com.gewu.domain.requirement;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 需求评论实体 — 需求讨论和沟通记录.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("requirement_comment")
public class RequirementComment extends BaseEntity {

    private String requirementId;
    private String content;
    private String parentId;
    private String attachments;
}
