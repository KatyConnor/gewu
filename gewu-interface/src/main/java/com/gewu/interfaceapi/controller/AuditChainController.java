package com.gewu.interfaceapi.controller;

import com.gewu.application.audit.AuditChainService;
import com.gewu.common.result.Result;
import com.gewu.domain.audit.AuditChainEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * WORM 审计链 API - 不可篡改的审计日志查询与校验。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/audit-chain")
@RequiredArgsConstructor
@Tag(name = "审计链", description = "WORM 审计链查询与完整性校验")
public class AuditChainController {

    private final AuditChainService auditChainService;

    @GetMapping("/verify")
    @Operation(summary = "校验审计链完整性", description = "逐条重算哈希检测篡改，返回链是否完整")
    public Result<Boolean> verifyChain() {
        boolean valid = auditChainService.verifyChain();
        return Result.success(valid, valid ? "审计链完整性验证通过" : "审计链存在篡改！");
    }

    @GetMapping("/executions/{executionId}")
    @Operation(summary = "按执行实例查询审计记录")
    public Result<List<AuditChainEntity>> queryByExecution(@PathVariable String executionId) {
        return Result.success(auditChainService.queryByExecution(executionId));
    }

    @GetMapping
    @Operation(summary = "查询审计记录列表")
    public Result<List<AuditChainEntity>> queryAll(@RequestParam(defaultValue = "100") int limit) {
        return Result.success(auditChainService.queryAll(limit));
    }
}