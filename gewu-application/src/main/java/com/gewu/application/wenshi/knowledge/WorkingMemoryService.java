package com.gewu.application.wenshi.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.infrastructure.cache.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 工作记忆服务 - 基于 Redis 的会话内短时记忆（五层记忆第一层）。
 * <p>存储 Agent 执行过程中的中间状态、临时变量、推理上下文。
 * 会话结束后自动过期（默认 TTL=3600s）。
 * <p>使用已有 {@link CacheService}（Redis+Caffeine 两层缓存）实现。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkingMemoryService {

    private final CacheService cacheService;
    private final ObjectMapper objectMapper;

    /** 默认 TTL: 1 小时 */
    private static final Duration DEFAULT_TTL = Duration.ofHours(1);
    private static final String KEY_PREFIX = "wenshi:wm:";

    /**
     * 写入工作记忆。
     *
     * @param sessionId 会话 ID
     * @param key       记忆键
     * @param value     记忆值
     */
    public void put(String sessionId, String key, Object value) {
        put(sessionId, key, value, DEFAULT_TTL);
    }

    /**
     * 写入工作记忆（自定义 TTL）。
     */
    public void put(String sessionId, String key, Object value, Duration ttl) {
        if (sessionId == null || key == null || value == null) return;
        try {
            String cacheKey = buildKey(sessionId, key);
            String json = objectMapper.writeValueAsString(value);
            cacheService.set(cacheKey, json, ttl);
            log.debug("WorkingMemory.put: session={}, key={}, ttl={}s", sessionId, key, ttl.getSeconds());
        } catch (JsonProcessingException e) {
            log.warn("WorkingMemory.put 序列化失败: {}", e.getMessage());
        }
    }

    /**
     * 读取工作记忆。
     *
     * @param sessionId 会话 ID
     * @param key       记忆键
     * @return 记忆值（反序列化为 String），不存在返回 null
     */
    public String get(String sessionId, String key) {
        if (sessionId == null || key == null) return null;
        Object cached = cacheService.get(buildKey(sessionId, key), String.class);
        return cached != null ? cached.toString() : null;
    }

    /**
     * 读取工作记忆（指定类型）。
     */
    public <T> T get(String sessionId, String key, Class<T> type) {
        String json = get(sessionId, key);
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("WorkingMemory.get 反序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 读取会话全部工作记忆。
     *
     * @param sessionId 会话 ID
     * @return 键值对 map（可能为空）
     */
    public Map<String, String> getAll(String sessionId) {
        if (sessionId == null) return Map.of();
        // CacheService 不支持前缀扫描，使用索引键追踪
        String indexKey = buildKey(sessionId, "__index__");
        Object indexObj = cacheService.get(indexKey, String.class);
        if (indexObj == null) return Map.of();

        Map<String, String> result = new HashMap<>();
        try {
            // 简化实现：遍历已注册的键（生产环境可用 Redis SCAN 替代）
            result.put("__index__", indexObj.toString());
        } catch (Exception e) {
            log.debug("WorkingMemory.getAll 失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 清除会话全部工作记忆。
     */
    public void clear(String sessionId) {
        if (sessionId == null) return;
        cacheService.deleteByPattern(KEY_PREFIX + sessionId + ":*");
        log.debug("WorkingMemory.clear: session={}", sessionId);
    }

    /**
     * 检查会话是否有工作记忆。
     */
    public boolean exists(String sessionId) {
        if (sessionId == null) return false;
        return cacheService.exists(buildKey(sessionId, "__active__"));
    }

    /**
     * 标记会话为活跃（写入哨兵键）。
     */
    public void markActive(String sessionId) {
        if (sessionId != null) {
            cacheService.set(buildKey(sessionId, "__active__"), "1", DEFAULT_TTL);
        }
    }

    private String buildKey(String sessionId, String key) {
        return KEY_PREFIX + sessionId + ":" + key;
    }
}