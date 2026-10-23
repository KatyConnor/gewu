package com.gewu.interfaceapi.controller;

import com.gewu.application.user.UserOptionQueryService;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户选项查询（新建项目"团队与角色"选择器数据源）.
 * <p>登录用户只读查询启用中的用户；完整用户管理在 admin-server
 * （用户管理权限门内），此处仅开放选择器所需的只读列表。
 */
@RestController
@RequestMapping("/api/v1/user-options")
@RequiredArgsConstructor
@Tag(name = "用户选项", description = "团队与角色选择器只读查询")
public class UserOptionController {

    private final UserOptionQueryService queryService;

    @GetMapping
    @Operation(summary = "启用用户选项列表", description = "keyword 模糊匹配显示名/用户名/邮箱")
    public Result<List<UserOptionQueryService.UserOption>> listOptions(
            @RequestParam(required = false) String keyword) {
        return Result.success(queryService.listEnabled(keyword));
    }
}
