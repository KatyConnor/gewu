package com.gewu.admin.controller;

import com.gewu.admin.dto.quota.QuotaPlanDTO;
import com.gewu.admin.dto.quota.SaveQuotaPlanCommand;
import com.gewu.admin.service.QuotaPlanService;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 配额套餐管理（管理员）：套餐 CRUD（含 5h/周/月/季窗口项）与用户绑定。
 *
 * @since 1.0.0
 */
@Slf4j
@Tag(name = "配额套餐管理", description = "管理员维护用户配额套餐与绑定（总量账本下发）")
@RestController
@RequestMapping("/api/v1/quota/plans")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class QuotaPlanController {

    private final QuotaPlanService quotaPlanService;

    @GetMapping
    @Operation(summary = "套餐列表", description = "含各时间窗口配额项")
    public Result<List<QuotaPlanDTO>> list() {
        return Result.success(quotaPlanService.list());
    }

    @PostMapping
    @Operation(summary = "创建套餐")
    public Result<QuotaPlanDTO> create(@Valid @RequestBody SaveQuotaPlanCommand command) {
        return Result.success(quotaPlanService.create(command));
    }

    @PutMapping("/{planId}")
    @Operation(summary = "更新套餐", description = "窗口项整体替换")
    public Result<QuotaPlanDTO> update(@PathVariable String planId,
                                       @Valid @RequestBody SaveQuotaPlanCommand command) {
        return Result.success(quotaPlanService.update(planId, command));
    }

    @DeleteMapping("/{planId}")
    @Operation(summary = "删除套餐", description = "已绑定用户自动视为无套餐")
    public Result<Void> delete(@PathVariable String planId) {
        quotaPlanService.delete(planId);
        return Result.success(null);
    }

    @PostMapping("/bindings/{userId}/{planId}")
    @Operation(summary = "绑定用户套餐", description = "一用户一生效绑定，重绑自动替换")
    public Result<Void> bind(@PathVariable String userId, @PathVariable String planId) {
        quotaPlanService.bind(userId, planId);
        return Result.success(null);
    }

    @DeleteMapping("/bindings/{userId}")
    @Operation(summary = "解绑用户套餐")
    public Result<Void> unbind(@PathVariable String userId) {
        quotaPlanService.unbind(userId);
        return Result.success(null);
    }
}
