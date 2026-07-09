package com.gewu.sandbox.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 沙箱审计日志 DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SandboxAuditDTO {

    private String id;
    private String sandboxId;
    private String action;
    private String userId;
    private Long timestamp;
    private String details;
}
