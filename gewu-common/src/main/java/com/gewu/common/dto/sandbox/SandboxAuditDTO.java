package com.gewu.common.dto.sandbox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 沙箱审计日志 DTO (前端 SandboxLogDTO).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SandboxAuditDTO {

    private String logId;
    private String sandboxId;
    private String action;
    private String status;
    private String detail;
    private String operatorId;
    private String operatorName;
    private Long createdAt;
}
