package com.gewu.admin.dto.skill;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 技能审核命令.
 */
@Data
public class AuditSkillCommand {

    @NotNull(message = "审核结果不能为空")
    private Boolean approved;

    /** 审核意见（拒绝时填写原因） */
    private String reason;
}
