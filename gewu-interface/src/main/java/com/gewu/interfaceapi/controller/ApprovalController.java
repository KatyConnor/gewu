package com.gewu.interfaceapi.controller;

import com.gewu.application.orchestration.OrchestrationService;
import com.gewu.common.context.UserContext;
import com.gewu.common.result.Result;
import com.gewu.domain.orchestration.ApprovalRequestEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * HITL 审批 API - 管理人工介入审批请求。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/approvals")
@RequiredArgsConstructor
@Tag(name = "HITL审批", description = "人工介入审批管理")
public class ApprovalController {

    private final OrchestrationService orchestrationService;

    @GetMapping("/pending")
    @Operation(summary = "查询待审批列表")
    public Result<List<ApprovalRequestEntity>> listPending() {
        return Result.success(orchestrationService.listPendingApprovals());
    }

    @PostMapping("/{requestId}/approve")
    @Operation(summary = "批准审批")
    public Result<Void> approve(
            @PathVariable String requestId,
            @RequestBody ApprovalActionRequest request) {
        String approver = UserContext.currentUserId();
        orchestrationService.approve(requestId, approver, request.getComment());
        return Result.success();
    }

    @PostMapping("/{requestId}/reject")
    @Operation(summary = "驳回审批")
    public Result<Void> reject(
            @PathVariable String requestId,
            @RequestBody ApprovalActionRequest request) {
        String approver = UserContext.currentUserId();
        orchestrationService.reject(requestId, approver, request.getComment());
        return Result.success();
    }

    @Data
    public static class ApprovalActionRequest {
        private String comment;
    }
}
