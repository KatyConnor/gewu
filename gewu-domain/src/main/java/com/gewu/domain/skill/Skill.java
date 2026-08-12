package com.gewu.domain.skill;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 技能实体.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "skill", autoResultMap = true)
public class Skill extends BaseEntity {

    private String skillName;
    private String description;
    private String category;
    private String content;
    private String emoji;

    /** 标签数组，以 JSON 存储 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    private Integer installCount;
    private Integer status;
    private Integer version;
    /** 发布状态：0=私有 1=待审核 2=已发布 3=已拒绝 */
    private Integer publishStatus;
}
