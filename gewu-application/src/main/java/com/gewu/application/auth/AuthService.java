package com.gewu.application.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.auth.dto.*;
import com.gewu.common.constant.CommonConstants;
import com.gewu.common.crypto.PasswordHasher;
import com.gewu.common.crypto.PasswordPolicy;
import com.gewu.common.enums.UserStatus;
import com.gewu.common.jwt.JwtUtil;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.user.*;
import com.gewu.application.workspace.WorkspaceService;
import com.gewu.infrastructure.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 认证应用服务 — 登录、注册、令牌刷新、登出.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserAccountMapper userMapper;
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final JwtUtil jwtUtil;
    private final PasswordPolicy passwordPolicy;
    private final com.gewu.infrastructure.cache.CacheService cacheService;
    private final WorkspaceService workspaceService;

    @Transactional
    public TokenDTO login(LoginCommand command) {
        UserAccount user = userMapper.selectOne(
                new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getUsername, command.getUsername()));

        if (user == null) {
            throw BusinessException.of(ResultCode.USER_NOT_FOUND);
        }
        if (user.getStatus() == UserStatus.LOCKED.getCode()) {
            throw BusinessException.of(ResultCode.USER_LOCKED);
        }
        if (user.getStatus() == UserStatus.DISABLED.getCode()) {
            throw BusinessException.of(ResultCode.USER_DISABLED);
        }

        if (!PasswordHasher.verify(command.getPassword(), user.getPasswordHash())) {
            int failCount = (user.getLoginFailCount() != null ? user.getLoginFailCount() : 0) + 1;
            user.setLoginFailCount(failCount);
            if (failCount >= CommonConstants.MAX_LOGIN_FAIL_COUNT) {
                user.setStatus(UserStatus.LOCKED.getCode());
                user.setLockedUntil(Instant.now().toEpochMilli() + CommonConstants.LOCK_DURATION_MS);
            }
            userMapper.updateById(user);
            log.warn("登录失败: username={}", com.gewu.common.util.LogMasking.maskUsername(command.getUsername()));
            throw BusinessException.of(ResultCode.PASSWORD_INCORRECT);
        }

        user.setLoginFailCount(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(Instant.now().toEpochMilli());
        userMapper.updateById(user);

        List<String> roleCodes = getRoleCodes(user.getId());
        java.util.Set<String> permissions = getPermissionsByUserId(user.getId());
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername(), roleCodes);
        String refreshToken = jwtUtil.generateRefreshToken(user.getId(), user.getUsername());

        return TokenDTO.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(CommonConstants.ACCESS_TOKEN_EXPIRATION_MS / 1000)
                .userId(user.getId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .roles(roleCodes)
                .permissions(new java.util.ArrayList<>(permissions))
                .build();
    }

    @Transactional
    public TokenDTO register(RegisterCommand command) {
        // 密码策略校验
        passwordPolicy.validate(command.getPassword(), command.getUsername());

        Long existing = userMapper.selectCount(
                new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getUsername, command.getUsername()));
        if (existing > 0) {
            throw BusinessException.of(ResultCode.USER_ALREADY_EXISTS);
        }

        existing = userMapper.selectCount(
                new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getEmail, command.getEmail()));
        if (existing > 0) {
            throw BusinessException.of(ResultCode.USER_ALREADY_EXISTS, "邮箱已被注册");
        }

        UserAccount user = new UserAccount();
        user.setId(Ulid.next());
        user.setUsername(command.getUsername());
        user.setEmail(command.getEmail());
        user.setPhone(command.getPhone());
        user.setDisplayName(command.getDisplayName());
        String passwordHash = PasswordHasher.hash(command.getPassword());
        user.setPasswordHash(passwordHash);
        user.setPasswordSalt(passwordHash.split("\\$")[1]);
        user.setStatus(UserStatus.ENABLED.getCode());
        user.setLoginFailCount(0);
        userMapper.insert(user);

        assignDefaultRole(user.getId());

        // 初始化用户工作空间（与注册同一事务，确保原子性）
        workspaceService.initWorkspace(user.getId());

        List<String> roleCodes = List.of("USER");
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername(), roleCodes);
        String refreshToken = jwtUtil.generateRefreshToken(user.getId(), user.getUsername());

        return TokenDTO.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(CommonConstants.ACCESS_TOKEN_EXPIRATION_MS / 1000)
                .userId(user.getId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .roles(roleCodes)
                .build();
    }

    public TokenDTO refresh(RefreshTokenCommand command) {
        String token = command.getRefreshToken();
        validateRefreshToken(token);
        validateNotBlacklisted(token);

        String userId = jwtUtil.getUserIdFromToken(token);
        String username = jwtUtil.getUsernameFromToken(token);
        String familyId = jwtUtil.getFamilyFromToken(token);
        String oldJti = jwtUtil.getJtiFromToken(token);
        List<String> roleCodes = getRoleCodes(userId);

        validateFamilyConsistency(familyId, oldJti);
        cacheService.blacklistToken(oldJti, java.time.Duration.ofDays(7));

        String accessToken = jwtUtil.generateAccessToken(userId, username, roleCodes);
        String newRefreshToken = jwtUtil.generateRefreshToken(userId, username);
        registerNewTokenFamily(newRefreshToken);

        return TokenDTO.builder()
                .accessToken(accessToken)
                .refreshToken(newRefreshToken)
                .tokenType("Bearer")
                .expiresIn(CommonConstants.ACCESS_TOKEN_EXPIRATION_MS / 1000)
                .userId(userId)
                .username(username)
                .roles(roleCodes)
                .build();
    }

    private void validateRefreshToken(String token) {
        if (!jwtUtil.validateToken(token)) {
            throw BusinessException.of(ResultCode.REFRESH_TOKEN_EXPIRED);
        }
        if (!jwtUtil.isRefreshToken(token)) {
            throw BusinessException.of(ResultCode.TOKEN_INVALID, "令牌类型不正确");
        }
    }

    private void validateNotBlacklisted(String token) {
        String jti = jwtUtil.getJtiFromToken(token);
        if (cacheService.isTokenBlacklisted(jti)) {
            String familyId = jwtUtil.getFamilyFromToken(token);
            if (familyId != null) {
                cacheService.deleteRefreshTokenFamily(familyId);
            }
            throw BusinessException.of(ResultCode.TOKEN_INVALID, "令牌已被吊销，可能存在盗用");
        }
    }

    private void validateFamilyConsistency(String familyId, String oldJti) {
        if (familyId == null) return;
        String storedJti = cacheService.getRefreshTokenFamily(familyId);
        if (storedJti != null && !storedJti.equals(oldJti)) {
            cacheService.deleteRefreshTokenFamily(familyId);
            throw BusinessException.of(ResultCode.TOKEN_INVALID, "令牌家族异常，可能存在盗用");
        }
    }

    private void registerNewTokenFamily(String newRefreshToken) {
        String newJti = jwtUtil.getJtiFromToken(newRefreshToken);
        String newFamily = jwtUtil.getFamilyFromToken(newRefreshToken);
        if (newFamily != null) {
            cacheService.storeRefreshTokenFamily(newFamily, newJti, java.time.Duration.ofDays(7));
        }
    }

    public void logout(String token) {
        try {
            String jti = jwtUtil.getJtiFromToken(token);
            cacheService.blacklistToken(jti, java.time.Duration.ofDays(7));
            log.info("用户登出: {}", jwtUtil.getUserIdFromToken(token));
        } catch (Exception e) {
            log.warn("登出时令牌解析失败");
        }
    }

    /**
     * 查询用户权限码集合（RBAC 运行时接通）.
     * <p>ADMIN 角色拥有全部权限（超权）；其他角色按 user_role -> role_permission -> permission 关联查询.
     */
    public Set<String> getPermissionsByUserId(String userId) {
        List<String> roleCodes = getRoleCodes(userId);
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

    private List<String> getRoleCodes(String userId) {
        List<UserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        if (userRoles.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> roleIds = userRoles.stream().map(UserRole::getRoleId).toList();
        List<Role> roles = roleMapper.selectBatchIds(roleIds);
        return roles.stream().map(Role::getRoleCode).toList();
    }

    /**
     * 查询当前用户的数据范围与机构 ID.
     * <p>取用户所有角色中 data_scope 最小值（最宽松），用于 DataPermissionInterceptor.
     *
     * @return [dataScope, orgId]，dataScope 默认 4（本人），orgId 可能为 null
     */
    public UserDataScope getUserDataScope(String userId) {
        UserAccount user = userMapper.selectById(userId);
        String orgId = user != null ? user.getOrgId() : null;

        List<UserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        if (userRoles.isEmpty()) {
            return new UserDataScope(4, orgId);
        }
        List<String> roleIds = userRoles.stream().map(UserRole::getRoleId).toList();
        List<Role> roles = roleMapper.selectBatchIds(roleIds);
        int minDataScope = roles.stream()
                .map(Role::getDataScope)
                .filter(java.util.Objects::nonNull)
                .min(Integer::compareTo)
                .orElse(4);
        return new UserDataScope(minDataScope, orgId);
    }

    /** 用户数据范围与机构 ID 携带对象. */
    public record UserDataScope(int dataScope, String orgId) {}

    private void assignDefaultRole(String userId) {
        Role defaultRole = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, "USER"));
        if (defaultRole != null) {
            UserRole userRole = new UserRole();
            userRole.setId(Ulid.next());
            userRole.setUserId(userId);
            userRole.setRoleId(defaultRole.getId());
            userRole.setSource("system");
            userRoleMapper.insert(userRole);
        }
    }
}