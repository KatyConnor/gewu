package com.gewu.admin.dto.requirement;

import lombok.Data;

/**
 * 管理端需求状态流转命令.
 */
@Data
public class AdminRequirementStatusCommand {

    /** 目标状态（RequirementStatus 编码） */
    private String status;

    /** 取消原因（status=CANCELLED 时使用） */
    private String reason;

    /** 评审类型（submit-review 时使用：REQUIREMENT/DESIGN） */
    private String reviewType;
}
