package com.gewu.interfaceapi.controller;

import com.gewu.application.auth.AuthService;
import com.gewu.application.auth.dto.LoginCommand;
import com.gewu.application.auth.dto.RefreshTokenCommand;
import com.gewu.application.auth.dto.RegisterCommand;
import com.gewu.application.auth.dto.TokenDTO;
import com.gewu.common.constant.CommonConstants;
import com.gewu.common.result.Result;
import com.gewu.common.context.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 认证接口 — 登录、注册、令牌刷新、登出.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "认证管理", description = "用户登录、注册、令牌刷新")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "用户名密码登录，返回 JWT 令牌")
    public Result<TokenDTO> login(@Valid @RequestBody LoginCommand command) {
        log.info("用户登录: {}", command.getUsername());
        return Result.success(authService.login(command));
    }

    @PostMapping("/register")
    @Operation(summary = "用户注册", description = "注册新用户账户")
    public Result<TokenDTO> register(@Valid @RequestBody RegisterCommand command) {
        log.info("用户注册: {}", command.getUsername());
        return Result.success(authService.register(command));
    }

    @PostMapping("/refresh")
    @Operation(summary = "刷新令牌", description = "使用刷新令牌获取新的访问令牌")
    public Result<TokenDTO> refresh(@Valid @RequestBody RefreshTokenCommand command) {
        return Result.success(authService.refresh(command));
    }

    @PostMapping("/logout")
    @Operation(summary = "用户登出", description = "登出并使当前令牌失效")
    public Result<Void> logout(HttpServletRequest request) {
        String bearer = request.getHeader(CommonConstants.AUTH_HEADER);
        if (bearer != null && bearer.startsWith(CommonConstants.BEARER_PREFIX)) {
            String token = bearer.substring(CommonConstants.BEARER_PREFIX.length());
            authService.logout(token);
        }
        return Result.success();
    }
}