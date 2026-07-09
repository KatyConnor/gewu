package com.gewu.application.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.auth.dto.*;
import com.gewu.common.constant.CommonConstants;
import com.gewu.common.crypto.PasswordHasher;
import com.gewu.common.enums.UserStatus;
import com.gewu.common.jwt.JwtUtil;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.user.*;
import com.gewu.infrastructure.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
            throw BusinessException.of(ResultCode.PASSWORD_INCORRECT);
        }

        user.setLoginFailCount(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(Instant.now().toEpochMilli());
        userMapper.updateById(user);

        List<String> roleCodes = getRoleCodes(user.getId());
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

    @Transactional
    public TokenDTO register(RegisterCommand command) {
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
        if (!jwtUtil.validateToken(command.getRefreshToken())) {
            throw BusinessException.of(ResultCode.REFRESH_TOKEN_EXPIRED);
        }
        if (!jwtUtil.isRefreshToken(command.getRefreshToken())) {
            throw BusinessException.of(ResultCode.TOKEN_INVALID, "令牌类型不正确");
        }

        String userId = jwtUtil.getUserIdFromToken(command.getRefreshToken());
        String username = jwtUtil.getUsernameFromToken(command.getRefreshToken());
        List<String> roleCodes = getRoleCodes(userId);

        String accessToken = jwtUtil.generateAccessToken(userId, username, roleCodes);
        String newRefreshToken = jwtUtil.generateRefreshToken(userId, username);

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

    public void logout(String token) {
        log.info("用户登出: {}", jwtUtil.getUserIdFromToken(token));
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