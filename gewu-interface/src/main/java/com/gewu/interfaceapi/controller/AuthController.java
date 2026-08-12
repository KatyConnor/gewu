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
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 认证接口 — 登录、注册、令牌刷新、登出.
 *
 * <p>CR-020: 支持 httpOnly Cookie 存储 Token，提高安全性。
 * 前端可以选择使用 Cookie 或响应体中的 Token。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "认证管理", description = "用户登录、注册、令牌刷新")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "用户名密码登录，返回 JWT 令牌。同时设置 httpOnly Cookie（可选）")
    public Result<TokenDTO> login(@Valid @RequestBody LoginCommand command, HttpServletResponse response) {
        log.info("用户登录: {}", command.getUsername());
        TokenDTO tokenDTO = authService.login(command);
        
        // CR-020: 设置 httpOnly Cookie 用于 Token 存储
        setAuthCookies(response, tokenDTO);
        
        return Result.success(tokenDTO);
    }

    @PostMapping("/register")
    @Operation(summary = "用户注册", description = "注册新用户账户")
    public Result<TokenDTO> register(@Valid @RequestBody RegisterCommand command, HttpServletResponse response) {
        log.info("用户注册: {}", command.getUsername());
        TokenDTO tokenDTO = authService.register(command);
        
        // CR-020: 设置 httpOnly Cookie 用于 Token 存储
        setAuthCookies(response, tokenDTO);
        
        return Result.success(tokenDTO);
    }

    @PostMapping("/refresh")
    @Operation(summary = "刷新令牌", description = "使用刷新令牌获取新的访问令牌")
    public Result<TokenDTO> refresh(@Valid @RequestBody RefreshTokenCommand command, HttpServletResponse response) {
        TokenDTO tokenDTO = authService.refresh(command);
        
        // CR-020: 更新 httpOnly Cookie
        setAuthCookies(response, tokenDTO);
        
        return Result.success(tokenDTO);
    }

    @PostMapping("/logout")
    @Operation(summary = "用户登出", description = "登出并使当前令牌失效")
    public Result<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String bearer = request.getHeader(CommonConstants.AUTH_HEADER);
        if (bearer != null && bearer.startsWith(CommonConstants.BEARER_PREFIX)) {
            String token = bearer.substring(CommonConstants.BEARER_PREFIX.length());
            authService.logout(token);
        }
        
        // CR-020: 清除认证 Cookie
        clearAuthCookies(response);
        
        return Result.success();
    }

    /**
     * 设置 httpOnly Cookie 用于 Token 存储。
     * Access Token 和 Refresh Token 都设置为 httpOnly，防止 XSS 攻击窃取。
     */
    private void setAuthCookies(HttpServletResponse response, TokenDTO tokenDTO) {
        // Access Token Cookie (30分钟)
        Cookie accessTokenCookie = new Cookie("access_token", tokenDTO.getAccessToken());
        accessTokenCookie.setHttpOnly(true);
        accessTokenCookie.setSecure(true); // 仅 HTTPS
        accessTokenCookie.setPath("/");
        accessTokenCookie.setMaxAge(1800); // 30分钟
        response.addCookie(accessTokenCookie);
        
        // Refresh Token Cookie (7天)
        Cookie refreshTokenCookie = new Cookie("refresh_token", tokenDTO.getRefreshToken());
        refreshTokenCookie.setHttpOnly(true);
        refreshTokenCookie.setSecure(true);
        refreshTokenCookie.setPath("/");
        refreshTokenCookie.setMaxAge(604800); // 7天
        response.addCookie(refreshTokenCookie);
    }

    /**
     * 清除认证 Cookie。
     */
    private void clearAuthCookies(HttpServletResponse response) {
        Cookie accessTokenCookie = new Cookie("access_token", "");
        accessTokenCookie.setHttpOnly(true);
        accessTokenCookie.setSecure(true);
        accessTokenCookie.setPath("/");
        accessTokenCookie.setMaxAge(0);
        response.addCookie(accessTokenCookie);
        
        Cookie refreshTokenCookie = new Cookie("refresh_token", "");
        refreshTokenCookie.setHttpOnly(true);
        refreshTokenCookie.setSecure(true);
        refreshTokenCookie.setPath("/");
        refreshTokenCookie.setMaxAge(0);
        response.addCookie(refreshTokenCookie);
    }
}