package com.gewu.admin.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.common.constant.CommonConstants;
import com.gewu.common.context.UserContext;
import com.gewu.common.jwt.JwtUtil;
import com.gewu.common.result.Result;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.user.Permission;
import com.gewu.domain.user.Role;
import com.gewu.domain.user.RolePermission;
import com.gewu.domain.user.UserAccount;
import com.gewu.domain.user.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.infrastructure.cache.CacheService;
import com.gewu.infrastructure.mapper.PermissionMapper;
import com.gewu.infrastructure.mapper.RoleMapper;
import com.gewu.infrastructure.mapper.RolePermissionMapper;
import com.gewu.infrastructure.mapper.UserAccountMapper;
import com.gewu.infrastructure.mapper.UserRoleMapper;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理服务 JWT 认证过滤器（主应用同源 token）。
 * <p>与主应用 JwtAuthenticationFilter 的差异：权限/数据范围不依赖 AuthService，
 * 直接共库查询（user_role/role_permission/permission/role/user_account），
 * 使 admin-server 无需依赖主应用 application 模块。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminJwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final CacheService cacheService;
    private final UserAccountMapper userAccountMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
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
        if (roleCodes == null) {
            roleCodes = List.of();
        }

        Set<String> permissions = resolvePermissions(userId, roleCodes);
        int dataScope = resolveDataScope(userId, roleCodes);
        String orgId = resolveOrgId(userId);

        UserContext context = UserContext.builder()
                .userId(userId)
                .username(username)
                .roleCodes(roleCodes)
                .permissions(permissions)
                .dataScope(dataScope)
                .orgId(orgId)
                .token(token)
                .build();
        UserContext.set(context);

        List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
        roleCodes.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);

        filterChain.doFilter(request, response);
    }

    /** 权限解析（与主应用 AuthService 同口径：ADMIN 直通全量权限）。 */
    private Set<String> resolvePermissions(String userId, List<String> roleCodes) {
        if (roleCodes.contains("ADMIN")) {
            return permissionMapper.selectList(null).stream()
                    .map(Permission::getPermissionCode)
                    .collect(Collectors.toSet());
        }
        List<UserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        if (userRoles.isEmpty()) return Collections.emptySet();
        List<String> roleIds = userRoles.stream().map(UserRole::getRoleId).toList();
        List<RolePermission> rps = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>().in(RolePermission::getRoleId, roleIds));
        if (rps.isEmpty()) return Collections.emptySet();
        List<String> permissionIds = rps.stream().map(RolePermission::getPermissionId).distinct().toList();
        return permissionMapper.selectBatchIds(permissionIds).stream()
                .map(Permission::getPermissionCode)
                .collect(Collectors.toSet());
    }

    /** 数据范围取角色最宽松值（与主应用 AuthService 同口径）。 */
    private int resolveDataScope(String userId, List<String> roleCodes) {
        List<UserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        if (userRoles.isEmpty()) {
            return 4;
        }
        List<String> roleIds = userRoles.stream().map(UserRole::getRoleId).toList();
        return roleMapper.selectBatchIds(roleIds).stream()
                .map(Role::getDataScope)
                .filter(java.util.Objects::nonNull)
                .min(Integer::compareTo)
                .orElse(4);
    }

    private String resolveOrgId(String userId) {
        UserAccount user = userAccountMapper.selectById(userId);
        return user != null ? user.getOrgId() : null;
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
