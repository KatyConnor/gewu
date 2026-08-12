package com.gewu.interfaceapi.controller;

import com.gewu.application.audit.AuditCenterService;
import com.gewu.application.audit.dto.PendingAuditDTO;
import com.gewu.application.skill.dto.AuditSkillCommand;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 审批中心接口 - 统一审批待办管理（管理员）.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/audits")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('audit:approve')")
@Tag(name = "审批中心", description = "统一审批待办管理")
public class AuditCenterController {

    private final AuditCenterService auditCenterService;

    @GetMapping("/pending")
    @Operation(summary = "待审核列表", description = "聚合技能发布与智能体上架待审核项")
    public Result<List<PendingAuditDTO>> pending() {
        return Result.success(auditCenterService.listPendingAudits());
    }

    @PostMapping("/{type}/{id}/approve")
    @Operation(summary = "审批通过")
    public Result<Void> approve(@PathVariable String type, @PathVariable String id) {
        auditCenterService.approve(type, id);
        return Result.success();
    }

    @PostMapping("/{type}/{id}/reject")
    @Operation(summary = "审批拒绝")
    public Result<Void> reject(@PathVariable String type, @PathVariable String id,
                                @Valid @RequestBody(required = false) AuditSkillCommand command) {
        String reason = command != null ? command.getReason() : null;
        auditCenterService.reject(type, id, reason);
        return Result.success();
    }
}
