package com.gewu.infrastructure.lock;

import com.gewu.common.constant.CommonConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分布式锁服务 — 基于 Redisson 的可重入锁.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedLockService {

    private final RedissonClient redissonClient;

    public boolean tryLock(String lockKey, Runnable action, long waitTime, long leaseTime) {
        RLock lock = redissonClient.getLock(CommonConstants.CACHE_LOCK_PREFIX + lockKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.MILLISECONDS);
            if (acquired) {
                action.run();
                return true;
            }
            log.warn("获取分布式锁失败: {}", lockKey);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取分布式锁被中断: {}", lockKey, e);
            return false;
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public <T> T tryLockAndReturn(String lockKey, Supplier<T> action, long waitTime, long leaseTime) {
        RLock lock = redissonClient.getLock(CommonConstants.CACHE_LOCK_PREFIX + lockKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.MILLISECONDS);
            if (acquired) {
                return action.get();
            }
            log.warn("获取分布式锁失败: {}", lockKey);
            throw new IllegalStateException("获取分布式锁失败: " + lockKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("获取分布式锁被中断: " + lockKey, e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}