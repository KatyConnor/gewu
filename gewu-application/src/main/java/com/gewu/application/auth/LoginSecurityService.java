package com.gewu.application.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.common.enums.UserStatus;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.user.UserAccount;
import com.gewu.infrastructure.cache.CacheService;
import com.gewu.infrastructure.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 登录安全策略 — 等保2.0 三级要求：启用登录失败锁定、IP 失败计数与封禁，依托 Redis 实现实时风控.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginSecurityService {

    private static final String LOGIN_LOCK_PREFIX = "gewu:login:lock:";
    private static final String LOGIN_FAIL_PREFIX = "gewu:login:fail:";
    private static final String LOGIN_IPBLOCK_PREFIX = "gewu:login:ipblock:";
    private static final String LOGIN_IP_FAIL_PREFIX = "gewu:login:ipfail:";

    private static final Duration LOCK_TTL = Duration.ofMinutes(30);
    private static final Duration FAIL_COUNTER_TTL = Duration.ofMinutes(5);
    private static final Duration IP_BLOCK_TTL = Duration.ofMinutes(5);

    private static final int MAX_USER_FAIL_COUNT = 5;
    private static final int MAX_IP_FAIL_COUNT = 20;

    private final UserAccountMapper userAccountMapper;
    private final CacheService cacheService;

    public void checkLoginAttempt(String username, String ip) {
        if (isUserLocked(username)) {
            throw BusinessException.of(ResultCode.USER_LOCKED);
        }
        if (isIpBlocked(ip)) {
            throw BusinessException.of(ResultCode.IP_BLOCKED);
        }
    }

    public void recordLoginFailure(String username, String ip) {
        String userFailKey = LOGIN_FAIL_PREFIX + username;
        cacheService.incrementWithExpire(userFailKey, LOCK_TTL);
        long userFailCount = cacheService.getCounter(userFailKey);

        if (userFailCount >= MAX_USER_FAIL_COUNT) {
            cacheService.set(LOGIN_LOCK_PREFIX + username, "1", LOCK_TTL);
            cacheService.delete(userFailKey);
            lockUserInDb(username);
            log.warn("用户登录失败次数超限，已锁定: {}", username);
        }

        String ipFailKey = LOGIN_IP_FAIL_PREFIX + ip;
        cacheService.incrementWithExpire(ipFailKey, FAIL_COUNTER_TTL);
        long ipFailCount = cacheService.getCounter(ipFailKey);
        if (ipFailCount >= MAX_IP_FAIL_COUNT) {
            cacheService.set(LOGIN_IPBLOCK_PREFIX + ip, "1", IP_BLOCK_TTL);
            cacheService.delete(ipFailKey);
            log.warn("IP 登录失败次数超限，已封禁: {}", ip);
        }
    }

    public void recordLoginSuccess(String username, String ip) {
        cacheService.delete(LOGIN_FAIL_PREFIX + username);
        cacheService.delete(LOGIN_LOCK_PREFIX + username);
        cacheService.delete(LOGIN_IP_FAIL_PREFIX + ip);
        cacheService.delete(LOGIN_IPBLOCK_PREFIX + ip);
    }

    public boolean isIpBlocked(String ip) {
        return cacheService.exists(LOGIN_IPBLOCK_PREFIX + ip);
    }

    public boolean isUserLocked(String username) {
        return cacheService.exists(LOGIN_LOCK_PREFIX + username);
    }

    private void lockUserInDb(String username) {
        UserAccount user = userAccountMapper.selectOne(
                new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getUsername, username));
        if (user != null && user.getStatus() == UserStatus.ENABLED.getCode()) {
            user.setStatus(UserStatus.LOCKED.getCode());
            user.setLockedUntil(Instant.now().toEpochMilli() + LOCK_TTL.toMillis());
            userAccountMapper.updateById(user);
        }
    }
}