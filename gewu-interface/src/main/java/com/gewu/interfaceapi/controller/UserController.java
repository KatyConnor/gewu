package com.gewu.interfaceapi.controller;

import com.gewu.application.user.UserService;
import com.gewu.application.user.dto.AssignOrgCommand;
import com.gewu.application.user.dto.AssignRolesCommand;
import com.gewu.application.user.dto.ResetPasswordCommand;
import com.gewu.application.user.dto.UpdateUserCommand;
import com.gewu.application.user.dto.UpdateUserStatusCommand;
import com.gewu.application.user.dto.UserDTO;
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
 * 用户接口 — 用户信息查询与更新.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "用户管理", description = "用户信息查询与管理")
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    @Operation(summary = "获取当前用户", description = "获取当前登录用户的详细信息")
    public Result<UserDTO> getCurrentUser() {
        return Result.success(userService.getCurrentUser());
    }

    @GetMapping("/{userId}")
    @Operation(summary = "获取用户信息", description = "根据用户ID获取用户信息")
    public Result<UserDTO> getUser(@PathVariable String userId) {
        return Result.success(userService.getUserById(userId));
    }

    @GetMapping
    @Operation(summary = "用户列表", description = "分页查询用户列表（管理员）")
    @PreAuthorize("hasAuthority('user:manage')")
    public Result<PageResult<UserDTO>> listUsers(@Valid PageQuery query,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String orgId) {
        return Result.success(userService.listUsers(query, keyword, orgId));
    }

    @PutMapping("/me")
    @Operation(summary = "更新当前用户", description = "更新当前登录用户的信息")
    public Result<UserDTO> updateCurrentUser(@Valid @RequestBody UpdateUserCommand command) {
        return Result.success(userService.updateUser(command));
    }

    @PutMapping("/{userId}/status")
    @Operation(summary = "更新用户状态", description = "启用/禁用/锁定用户（管理员）")
    @PreAuthorize("hasAuthority('user:manage')")
    public Result<Void> updateUserStatus(@PathVariable String userId,
                                          @Valid @RequestBody UpdateUserStatusCommand command) {
        userService.updateUserStatus(userId, command.getStatus());
        return Result.success();
    }

    @PostMapping("/{userId}/reset-password")
    @Operation(summary = "重置用户密码", description = "管理员重置用户密码")
    @PreAuthorize("hasAuthority('user:manage')")
    public Result<Void> resetPassword(@PathVariable String userId,
                                       @Valid @RequestBody ResetPasswordCommand command) {
        userService.resetPassword(userId, command.getNewPassword());
        return Result.success();
    }

    @PutMapping("/{userId}/roles")
    @Operation(summary = "分配角色", description = "管理员分配用户角色")
    @PreAuthorize("hasAuthority('user:manage')")
    public Result<Void> assignRoles(@PathVariable String userId,
                                     @Valid @RequestBody AssignRolesCommand command) {
        userService.assignRoles(userId, command.getRoleCodes());
        return Result.success();
    }

    @PutMapping("/{userId}/org")
    @Operation(summary = "分配机构", description = "管理员分配用户所属机构")
    @PreAuthorize("hasAuthority('user:manage')")
    public Result<Void> assignOrg(@PathVariable String userId,
                                   @Valid @RequestBody AssignOrgCommand command) {
        userService.assignOrg(userId, command.getOrgId());
        return Result.success();
    }
}