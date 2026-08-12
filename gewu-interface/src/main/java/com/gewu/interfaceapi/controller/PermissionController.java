package com.gewu.interfaceapi.controller;

import com.gewu.application.user.PermissionService;
import com.gewu.application.user.dto.CreatePermissionCommand;
import com.gewu.application.user.dto.PermissionDTO;
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
 * 权限管理接口 - 权限定义的查询与维护（管理员）.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/permissions")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('role:manage')")
@Tag(name = "权限管理", description = "权限定义管理")
public class PermissionController {

    private final PermissionService permissionService;

    @GetMapping
    @Operation(summary = "权限列表", description = "获取全部权限定义")
    public Result<List<PermissionDTO>> list() {
        return Result.success(permissionService.listPermissions());
    }

    @PostMapping
    @Operation(summary = "创建权限")
    public Result<PermissionDTO> create(@Valid @RequestBody CreatePermissionCommand command) {
        log.info("创建权限: {}", command.getPermissionCode());
        return Result.success(permissionService.createPermission(command));
    }

    @DeleteMapping("/{permissionId}")
    @Operation(summary = "删除权限", description = "删除权限并清理角色关联")
    public Result<Void> delete(@PathVariable String permissionId) {
        log.info("删除权限: {}", permissionId);
        permissionService.deletePermission(permissionId);
        return Result.success();
    }
}
