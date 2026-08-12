package com.gewu.infrastructure.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 缓存服务 - 多级缓存（Caffeine 本地 + Redis/DragonflyDB），支持泛型对象缓存.
 * <p>
 * 读取流程：本地 Caffeine -> Redis -> 返回 null。Redis 命中时自动回填本地缓存。
 * 写入流程：同时写入本地和 Redis。删除时同时失效两层。
 * 本地缓存容量 1000 条、TTL 5 分钟，作为 Redis 的热点缓存层。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 本地缓存层（Caffeine），容量 1000，写入后 5 分钟过期 */
    private final Cache<String, String> localCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    public void set(String key, Object value, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, ttl);
            localCache.put(key, json);
        } catch (Exception e) {
            log.error("缓存写入失败: key={}", key, e);
        }
    }

    public <T> T get(String key, Class<T> type) {
        // 1. 检查本地缓存
        String json = localCache.getIfPresent(key);
        if (json != null) {
            try {
                return objectMapper.readValue(json, type);
            } catch (Exception e) {
                localCache.invalidate(key);
            }
        }
        // 2. 检查 Redis
        try {
            json = redisTemplate.opsForValue().get(key);
            if (json == null) return null;
            // 回填本地缓存
            localCache.put(key, json);
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.error("缓存读取失败: key={}", key, e);
            return null;
        }
    }

    public <T> T get(String key, TypeReference<T> typeRef) {
        // 1. 检查本地缓存
        String json = localCache.getIfPresent(key);
        if (json != null) {
            try {
                return objectMapper.readValue(json, typeRef);
            } catch (Exception e) {
                localCache.invalidate(key);
            }
        }
        // 2. 检查 Redis
        try {
            json = redisTemplate.opsForValue().get(key);
            if (json == null) return null;
            localCache.put(key, json);
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.error("缓存读取失败: key={}", key, e);
            return null;
        }
    }

    public void delete(String key) {
        localCache.invalidate(key);
        redisTemplate.delete(key);
    }

    public void deleteByPattern(String pattern) {
        // 清空本地缓存（模式匹配清空成本高，直接全量清空）
        localCache.invalidateAll();
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("Redis 模式删除失败: pattern={}", pattern);
        }
    }

    public boolean exists(String key) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.warn("Redis 连接失败，跳过存在性检查: key={}", key);
            return false;
        }
    }

    public void increment(String key) {
        try {
            redisTemplate.opsForValue().increment(key);
        } catch (Exception e) {
            log.warn("Redis 计数器递增失败: key={}", key);
        }
    }

    public Long getCounter(String key) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            return value != null ? Long.parseLong(value) : 0L;
        } catch (Exception e) {
            log.warn("Redis 计数器读取失败: key={}", key);
            return 0L;
        }
    }

    public void incrementWithExpire(String key, Duration ttl) {
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, ttl);
            }
        } catch (Exception e) {
            log.warn("Redis 计数器递增失败: key={}", key);
        }
    }

    public void blacklistToken(String jti, Duration ttl) {
        try {
            redisTemplate.opsForValue().set("gewu:token:blacklist:" + jti, "1", ttl);
        } catch (Exception e) {
            log.warn("Redis 令牌黑名单写入失败: jti={}", jti);
        }
    }

    public boolean isTokenBlacklisted(String jti) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey("gewu:token:blacklist:" + jti));
        } catch (Exception e) {
            log.warn("Redis 连接失败，跳过令牌黑名单检查: jti={}", jti);
            return false; // Redis 不可用时，默认不阻止请求（降级策略）
        }
    }

    public void storeRefreshTokenFamily(String familyId, String tokenJti, Duration ttl) {
        try {
            redisTemplate.opsForValue().set("gewu:token:family:" + familyId, tokenJti, ttl);
        } catch (Exception e) {
            log.warn("Redis 刷新令牌家族写入失败: familyId={}", familyId);
        }
    }

    public String getRefreshTokenFamily(String familyId) {
        try {
            return redisTemplate.opsForValue().get("gewu:token:family:" + familyId);
        } catch (Exception e) {
            log.warn("Redis 刷新令牌家族读取失败: familyId={}", familyId);
            return null;
        }
    }

    public void deleteRefreshTokenFamily(String familyId) {
        try {
            redisTemplate.delete("gewu:token:family:" + familyId);
        } catch (Exception e) {
            log.warn("Redis 刷新令牌家族删除失败: familyId={}", familyId);
        }
    }
}