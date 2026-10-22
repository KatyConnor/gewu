package com.gewu.interfaceapi.controller;

import com.gewu.application.appsystem.AppSystemQueryService;
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
 * 应用系统选项查询（设计文档 58 关联：新建项目"所属系统"选择器数据源）.
 * <p>登录用户只读查询管理端配置的启用系统；管理端 CRUD 在 admin-server
 * （appsystem:manage 权限门内），此处仅开放选择器所需的只读列表。
 */
@RestController
@RequestMapping("/api/v1/app-system-options")
@RequiredArgsConstructor
@Tag(name = "应用系统选项", description = "所属系统选择器只读查询")
public class AppSystemOptionController {

    private final AppSystemQueryService queryService;

    @GetMapping
    @Operation(summary = "启用系统选项列表", description = "keyword 模糊匹配系统名称/编码/简称")
    public Result<List<AppSystemQueryService.AppSystemOption>> listOptions(
            @RequestParam(required = false) String keyword) {
        return Result.success(queryService.listEnabled(keyword));
    }
}
