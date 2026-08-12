package com.gewu.application.skill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建技能命令.
 */
@Data
public class CreateSkillCommand {

    @NotBlank(message = "技能名称不能为空")
    @Size(max = 128, message = "技能名称最长 128 个字符")
    private String skillName;

    @Size(max = 1024, message = "描述最长 1024 个字符")
    private String description;

    @Size(max = 64, message = "分类最长 64 个字符")
    private String category;

    private String content;

    @Size(max = 16, message = "emoji 最长 16 个字符")
    private String emoji;

    private List<String> tags;

    private Integer status = 1;
}
