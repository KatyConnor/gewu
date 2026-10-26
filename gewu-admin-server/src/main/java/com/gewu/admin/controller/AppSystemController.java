package com.gewu.admin.controller;

import com.gewu.admin.dto.appsystem.*;
import com.gewu.admin.service.AppSystemService;
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
 * 应用系统管理接口 - 应用系统台账与子模块服务 CRUD.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/app-systems")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('appsystem:manage')")
@Tag(name = "应用系统管理", description = "应用系统台账与子模块服务管理")
public class AppSystemController {

    private final AppSystemService appSystemService;

    // ==================== 应用系统 ====================

    @GetMapping
    @Operation(summary = "系统列表", description = "keyword 模糊匹配编码/名称/简称（含服务数与机构名）")
    public Result<List<AppSystemDTO>> list(@RequestParam(required = false) String keyword) {
        return Result.success(appSystemService.listSystems(keyword));
    }

    @PostMapping
    @Operation(summary = "创建应用系统")
    public Result<AppSystemDTO> create(@Valid @RequestBody CreateAppSystemCommand command) {
        log.info("创建应用系统: {}", command.getSystemCode());
        return Result.success(appSystemService.createSystem(command));
    }

    @PutMapping("/{systemId}")
    @Operation(summary = "更新应用系统", description = "系统编码不可变")
    public Result<AppSystemDTO> update(@PathVariable String systemId,
                                       @Valid @RequestBody UpdateAppSystemCommand command) {
        return Result.success(appSystemService.updateSystem(systemId, command));
    }

    @DeleteMapping("/{systemId}")
    @Operation(summary = "删除应用系统", description = "存在子模块服务时无法删除")
    public Result<Void> delete(@PathVariable String systemId) {
        log.info("删除应用系统: {}", systemId);
        appSystemService.deleteSystem(systemId);
        return Result.success();
    }

    // ==================== 子模块服务 ====================

    @GetMapping("/{systemId}/services")
    @Operation(summary = "服务列表", description = "指定系统下的子模块服务")
    public Result<List<AppServiceDTO>> listServices(@PathVariable String systemId) {
        return Result.success(appSystemService.listServices(systemId));
    }

    @PostMapping("/{systemId}/services")
    @Operation(summary = "创建子模块服务")
    public Result<AppServiceDTO> createService(@PathVariable String systemId,
                                               @Valid @RequestBody CreateAppServiceCommand command) {
        log.info("创建子模块服务: systemId={}, code={}", systemId, command.getServiceCode());
        return Result.success(appSystemService.createService(systemId, command));
    }

    @PutMapping("/{systemId}/services/{serviceId}")
    @Operation(summary = "更新子模块服务", description = "服务编码不可变")
    public Result<AppServiceDTO> updateService(@PathVariable String systemId, @PathVariable String serviceId,
                                               @Valid @RequestBody UpdateAppServiceCommand command) {
        return Result.success(appSystemService.updateService(systemId, serviceId, command));
    }

    @DeleteMapping("/{systemId}/services/{serviceId}")
    @Operation(summary = "删除子模块服务")
    public Result<Void> deleteService(@PathVariable String systemId, @PathVariable String serviceId) {
        log.info("删除子模块服务: systemId={}, serviceId={}", systemId, serviceId);
        appSystemService.deleteService(systemId, serviceId);
        return Result.success();
    }
}
