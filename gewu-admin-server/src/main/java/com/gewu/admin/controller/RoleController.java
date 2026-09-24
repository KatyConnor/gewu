package com.gewu.admin.controller;

import com.gewu.admin.service.RoleService;
import com.gewu.admin.dto.role.AssignPermissionsCommand;
import com.gewu.admin.dto.role.CreateRoleCommand;
import com.gewu.admin.dto.role.PermissionDTO;
import com.gewu.admin.dto.role.RoleDTO;
import com.gewu.admin.dto.role.UpdateRoleCommand;
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
 * 角色管理接口 - 角色 CRUD 与权限分配（管理员）.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/roles")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('role:manage')")
@Tag(name = "角色管理", description = "角色与权限管理")
public class RoleController {

    private final RoleService roleService;

    @GetMapping
    @Operation(summary = "角色列表", description = "获取全部角色（含权限码与用户数）")
    public Result<List<RoleDTO>> list() {
        return Result.success(roleService.listRoles());
    }

    @GetMapping("/{roleId}")
    @Operation(summary = "获取角色")
    public Result<RoleDTO> get(@PathVariable String roleId) {
        return Result.success(roleService.getRole(roleId));
    }

    @PostMapping
    @Operation(summary = "创建角色")
    public Result<RoleDTO> create(@Valid @RequestBody CreateRoleCommand command) {
        log.info("创建角色: {}", command.getRoleCode());
        return Result.success(roleService.createRole(command));
    }

    @PutMapping("/{roleId}")
    @Operation(summary = "更新角色")
    public Result<RoleDTO> update(@PathVariable String roleId, @Valid @RequestBody UpdateRoleCommand command) {
        return Result.success(roleService.updateRole(roleId, command));
    }

    @DeleteMapping("/{roleId}")
    @Operation(summary = "删除角色", description = "系统预置角色不可删除")
    public Result<Void> delete(@PathVariable String roleId) {
        log.info("删除角色: {}", roleId);
        roleService.deleteRole(roleId);
        return Result.success();
    }

    @GetMapping("/{roleId}/permissions")
    @Operation(summary = "角色权限列表")
    public Result<List<PermissionDTO>> listPermissions(@PathVariable String roleId) {
        return Result.success(roleService.listRolePermissions(roleId));
    }

    @PutMapping("/{roleId}/permissions")
    @Operation(summary = "分配角色权限", description = "替换角色的权限集合")
    public Result<Void> assignPermissions(@PathVariable String roleId,
                                           @Valid @RequestBody AssignPermissionsCommand command) {
        log.info("分配角色权限: roleId={}", roleId);
        roleService.assignPermissions(roleId, command.getPermissionCodes());
        return Result.success();
    }
}
