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
@Tag(name = "用户信息", description = "当前登录用户信息（管理 CRUD 已迁后台管理服务）")
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    @Operation(summary = "获取当前用户", description = "获取当前登录用户的详细信息")
    public Result<UserDTO> getCurrentUser() {
        return Result.success(userService.getCurrentUser());
    }



    @PutMapping("/me")
    @Operation(summary = "更新当前用户", description = "更新当前登录用户的信息")
    public Result<UserDTO> updateCurrentUser(@Valid @RequestBody UpdateUserCommand command) {
        return Result.success(userService.updateUser(command));
    }




}