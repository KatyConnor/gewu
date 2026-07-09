package com.gewu.sandbox.controller;

import com.gewu.common.result.Result;
import com.gewu.sandbox.audit.SandboxAuditService;
import com.gewu.sandbox.dto.SandboxAuditDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 沙箱审计接口.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sandboxes")
@RequiredArgsConstructor
@Tag(name = "沙箱审计", description = "沙箱审计日志查询")
public class SandboxAuditController {

    private final SandboxAuditService auditService;

    @GetMapping("/{id}/audit")
    @Operation(summary = "获取沙箱审计日志", description = "获取指定沙箱的审计日志")
    public Result<List<SandboxAuditDTO>> getSandboxAuditLogs(@PathVariable String id) {
        return Result.success(auditService.getAuditLogsBySandboxId(id));
    }

    @GetMapping("/audit")
    @Operation(summary = "获取所有审计日志", description = "获取所有沙箱审计日志（仅管理员）")
    public Result<List<SandboxAuditDTO>> getAllAuditLogs() {
        return Result.success(auditService.getAllAuditLogs());
    }
}
