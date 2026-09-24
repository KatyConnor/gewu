package com.gewu.interfaceapi.controller;

import com.gewu.application.quota.UserPreferenceService;
import com.gewu.application.quota.UserQuotaService;
import com.gewu.application.quota.dto.QuotaPreflightResultDTO;
import com.gewu.application.quota.dto.UserPreferenceDTO;
import com.gewu.common.context.UserContext;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户偏好（当前登录用户）：配额提醒阈值与熔断开关 + 当前套餐余量。
 *
 * @since 1.0.0
 */
@Slf4j
@Tag(name = "用户偏好", description = "配额提醒阈值与熔断开关（用户级设置）")
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserPreferenceController {

    private final UserPreferenceService userPreferenceService;
    private final UserQuotaService userQuotaService;

    @GetMapping("/preferences")
    @Operation(summary = "查询我的偏好", description = "无记录时返回缺省值（阈值 80%、仅提醒）")
    public Result<UserPreferenceDTO> get() {
        return Result.success(userPreferenceService.get(UserContext.currentUserId()));
    }

    @PutMapping("/preferences")
    @Operation(summary = "更新我的偏好", description = "字段为 null 时保持原值")
    public Result<UserPreferenceDTO> update(@RequestBody UserPreferenceDTO command) {
        String userId = UserContext.currentUserId();
        userPreferenceService.update(userId, command.getQuotaAlertThreshold(), command.getQuotaBlockEnabled());
        return Result.success(userPreferenceService.get(userId));
    }

    @GetMapping("/quota")
    @Operation(summary = "查询我的套餐余量", description = "未绑定套餐时 bound=false（不限量）")
    public Result<QuotaPreflightResultDTO> quota() {
        return Result.success(userQuotaService.preflight(UserContext.currentUserId()));
    }
}
