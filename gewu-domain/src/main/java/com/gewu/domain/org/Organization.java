package com.gewu.domain.org;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 组织机构实体 - 树形结构.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("organization")
public class Organization extends BaseEntity {

    /** 父机构ID（NULL 为顶级） */
    private String parentId;
    /** 机构名称 */
    private String orgName;
    /** 机构编码 */
    private String orgCode;
    /** 排序号 */
    private Integer sortOrder;
}
