package com.gewu.admin.dto.skill;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 技能 DTO.
 */
@Data
@Builder
public class SkillDTO {

    private String skillId;
    private String skillName;
    private String description;
    private String category;
    private String content;
    private String emoji;
    private List<String> tags;
    private Integer installCount;
    private Integer status;
    private Integer version;
    /** 发布状态：0=私有 1=待审核 2=已发布 3=已拒绝 */
    private Integer publishStatus;
    private String publishStatusDesc;
    private Long createdAt;
    private String createdBy;
}
