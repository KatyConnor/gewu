package com.gewu.interfaceconfig.security;

import com.gewu.common.constant.CommonConstants;
import com.gewu.common.context.UserContext;
import com.gewu.common.jwt.JwtUtil;
import com.gewu.common.result.Result;
import com.gewu.common.result.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JWT 认证过滤器 — 从 Authorization 头解析 JWT 并设置 SecurityContext.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final com.gewu.infrastructure.cache.CacheService cacheService;
    private final com.gewu.application.auth.AuthService authService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 在请求开始时清理上一轮请求可能残留的 ThreadLocal（防止线程池复用导致上下文泄漏）
        UserContext.clear();

        String token = resolveToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!jwtUtil.validateToken(token)) {
            writeUnauthorized(response, ResultCode.TOKEN_EXPIRED);
            return;
        }

        String jti = jwtUtil.getJtiFromToken(token);
        if (cacheService.isTokenBlacklisted(jti)) {
            writeUnauthorized(response, ResultCode.TOKEN_INVALID);
            return;
        }

        Claims claims = jwtUtil.parseToken(token);
        String userId = claims.getSubject();
        String username = claims.get("username", String.class);
        @SuppressWarnings("unchecked")
        List<String> roleCodes = claims.get("roles", List.class);

        Set<String> permissions = authService.getPermissionsByUserId(userId);
        com.gewu.application.auth.AuthService.UserDataScope dataScope = authService.getUserDataScope(userId);
        UserContext context = UserContext.builder()
                .userId(userId)
                .username(username)
                .roleCodes(roleCodes != null ? roleCodes : List.of())
                .permissions(permissions)
                .dataScope(dataScope.dataScope())
                .orgId(dataScope.orgId())
                .token(token)
                .build();
        UserContext.set(context);

        List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
        (roleCodes != null ? roleCodes : List.<String>of())
                .forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);

        // 不在 finally 中清理 SecurityContext 和 UserContext：
        // Spring MVC 的 Flux/SSE 异步处理在 filterChain.doFilter() 返回后继续执行，
        // finally 清理会导致异步线程和 ERROR dispatch 中 SecurityContext 丢失，
        // 进而触发 AccessDeniedException + "response is already committed"。
        // SecurityContext 由 Spring Security 的 SecurityContextHolderFilter 负责清理；
        // UserContext 在下一轮请求开始时清理（上方 UserContext.clear()）。
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader(CommonConstants.AUTH_HEADER);
        if (StringUtils.hasText(bearer) && bearer.startsWith(CommonConstants.BEARER_PREFIX)) {
            return bearer.substring(CommonConstants.BEARER_PREFIX.length());
        }
        return null;
    }

    private void writeUnauthorized(HttpServletResponse response, ResultCode resultCode) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(resultCode)));
    }
}