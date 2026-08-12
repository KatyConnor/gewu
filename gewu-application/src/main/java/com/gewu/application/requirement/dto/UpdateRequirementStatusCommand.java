package com.gewu.application.requirement.dto;

import lombok.Data;

/**
 * 更新需求状态命令.
 */
@Data
public class UpdateRequirementStatusCommand {

    private String status;
    private String reason;
}
