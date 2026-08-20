package com.gewu.interfaceapi.controller;

import com.gewu.application.governance.DbPolicyServiceAdapter;
import com.gewu.common.context.UserContext;
import com.gewu.common.result.Result;
import com.gewu.domain.governance.GovernancePolicyEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 治理策略 API - 策略 CRUD、版本查询、灰度激活与回滚。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/policies")
@RequiredArgsConstructor
@Tag(name = "治理策略", description = "Agent 行为策略管理（版本化/可回滚）")
public class PolicyController {

    private final DbPolicyServiceAdapter policyService;

    @GetMapping
    @Operation(summary = "查询场景策略版本列表", description = "按版本号降序返回场景全部策略版本")
    public Result<List<GovernancePolicyEntity>> listPolicies(@RequestParam String scenario) {
        return Result.success(policyService.listVersions(scenario));
    }

    @GetMapping("/active")
    @Operation(summary = "查询场景当前生效策略")
    public Result<Map<String, Object>> getActivePolicy(@RequestParam String scenario) {
        return Result.success(policyService.getActivePolicy(scenario));
    }

    @PostMapping
    @Operation(summary = "创建策略新版本", description = "同场景版本号自动递增，创建后默认不激活")
    public Result<GovernancePolicyEntity> createPolicy(@RequestBody CreatePolicyRequest request) {
        String userId = UserContext.currentUserId();
        return Result.success(policyService.createPolicy(
                request.getScenario(), request.getPolicyName(),
                request.getRuleJson(), request.getDescription(), userId));
    }

    @PostMapping("/{policyId}/activate")
    @Operation(summary = "激活策略版本", description = "同场景互斥激活，立即生效")
    public Result<Void> activate(@PathVariable String policyId) {
        policyService.activate(policyId, UserContext.currentUserId());
        return Result.success();
    }

    @PostMapping("/rollback")
    @Operation(summary = "回滚场景策略到上一版本")
    public Result<Boolean> rollback(@RequestParam String scenario) {
        return Result.success(policyService.rollbackPolicy(scenario));
    }

    @GetMapping("/check")
    @Operation(summary = "校验动作是否符合策略")
    public Result<Boolean> checkPolicy(@RequestParam String scenario, @RequestParam String action) {
        return Result.success(policyService.checkPolicy(scenario, action));
    }

    @Data
    public static class CreatePolicyRequest {
        private String scenario;
        private String policyName;
        /** 策略规则 JSON（allow/deny + 场景自定义参数） */
        private String ruleJson;
        private String description;
    }
}
