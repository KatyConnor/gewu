package com.gewu.admin.controller;

import com.gewu.admin.dto.user.AssignOrgCommand;
import com.gewu.admin.dto.user.AssignRolesCommand;
import com.gewu.admin.dto.user.ResetPasswordCommand;
import com.gewu.admin.dto.user.UpdateUserStatusCommand;
import com.gewu.admin.dto.user.UserDTO;
import com.gewu.admin.service.AdminUserService;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 用户管理接口（管理端）：列表/状态/重置密码/分配角色机构。
 * 当前用户信息（/users/me）保留在主应用。
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "用户管理", description = "后台管理端 - 用户信息管理")
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping("/{userId}")
    @Operation(summary = "获取用户信息", description = "根据用户ID获取用户信息")
    public Result<UserDTO> getUser(@PathVariable String userId) {
        return Result.success(adminUserService.getUserById(userId));
    }

    @GetMapping
    @Operation(summary = "用户列表", description = "分页查询用户列表（管理员）")
    @PreAuthorize("hasAuthority('user:manage')")
    public Result<PageResult<UserDTO>> listUsers(@Valid PageQuery query,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String orgId) {
        return Result.success(adminUserService.listUsers(query, keyword, orgId));
    }

    @PutMapping("/{userId}/status")
    @Operation(summary = "更新用户状态", description = "启用/禁用/锁定用户（管理员）")
    @PreAuthorize("hasAuthority('user:manage')")
    public Result<Void> updateUserStatus(@PathVariable String userId,
                                          @Valid @RequestBody UpdateUserStatusCommand command) {
        adminUserService.updateUserStatus(userId, command.getStatus());
        return Result.success();
    }

    @PostMapping("/{userId}/reset-password")
    @Operation(summary = "重置用户密码", description = "管理员重置用户密码")
    @PreAuthorize("hasAuthority('user:manage')")
    public Result<Void> resetPassword(@PathVariable String userId,
                                       @Valid @RequestBody ResetPasswordCommand command) {
        adminUserService.resetPassword(userId, command.getNewPassword());
        return Result.success();
    }

    @PutMapping("/{userId}/roles")
    @Operation(summary = "分配角色", description = "管理员分配用户角色")
    @PreAuthorize("hasAuthority('user:manage')")
    public Result<Void> assignRoles(@PathVariable String userId,
                                     @Valid @RequestBody AssignRolesCommand command) {
        adminUserService.assignRoles(userId, command.getRoleCodes());
        return Result.success();
    }

    @PutMapping("/{userId}/org")
    @Operation(summary = "分配机构", description = "管理员分配用户所属机构")
    @PreAuthorize("hasAuthority('user:manage')")
    public Result<Void> assignOrg(@PathVariable String userId,
                                   @Valid @RequestBody AssignOrgCommand command) {
        adminUserService.assignOrg(userId, command.getOrgId());
        return Result.success();
    }
}
