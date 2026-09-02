package com.gewu.application.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * SSE 跨实例广播发布器（T5.1 多副本改造）。
 * <p>仅当 {@code gewu.sse.distributed=true} 且提供 RedisTemplate 时启用：
 * 本地直发之外的跨实例投递经 Redis 频道 {@code gewu:sse:broadcast} 发布，
 * 各实例的订阅器（{@link SseDistributedConfig}）接收后按 scope 路由本地投递。
 * 分布式关闭时本组件不存在，SseEventManager 退化为单实例行为。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "gewu.sse.distributed", havingValue = "true")
public class SseBroadcastService {

    public static final String CHANNEL = "gewu:sse:broadcast";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    /** 本实例唯一标识（防自环） */
    private final String instanceId = java.util.UUID.randomUUID().toString();

    public SseBroadcastService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        log.info("SseBroadcastService 启用: instanceId={}, channel={}", instanceId, CHANNEL);
    }

    /** 发布会话范围事件（origin 实例的本地直发由调用方完成，此处仅跨实例） */
    public void broadcastSession(String sessionId, String eventName, Object data) {
        publish(new SseBroadcastMessage(instanceId, SseBroadcastMessage.SCOPE_SESSION,
                sessionId, eventName, toJson(data)));
    }

    /** 发布用户范围事件 */
    public void broadcastUser(String userId, String eventName, Object data) {
        publish(new SseBroadcastMessage(instanceId, SseBroadcastMessage.SCOPE_USER,
                userId, eventName, toJson(data)));
    }

    /** 发布 HITL 决策回传（approvalId -> 持有挂起 Sink 的实例） */
    public void relayDecision(String approvalId, Object decision) {
        publish(new SseBroadcastMessage(instanceId, SseBroadcastMessage.SCOPE_HITL,
                approvalId, null, toJson(decision)));
    }

    private void publish(SseBroadcastMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(CHANNEL, json);
            log.debug("SSE 广播发布: scope={}, target={}", message.scope(), message.target());
        } catch (Exception e) {
            log.warn("SSE 广播发布失败（本地投递不受影响）: scope={}, cause={}",
                    message.scope(), e.getMessage());
        }
    }

    /** 解析订阅消息（供订阅器路由） */
    public SseBroadcastMessage parse(String raw) {
        try {
            return objectMapper.readValue(raw, SseBroadcastMessage.class);
        } catch (Exception e) {
            log.warn("SSE 广播消息解析失败（丢弃）: {}", e.getMessage());
            return null;
        }
    }

    public String getInstanceId() {
        return instanceId;
    }

    /** 载荷序列化：字符串原样透传，其余对象转 JSON（包级可见供测试） */
    String toJson(Object data) {
        if (data instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            return String.valueOf(data);
        }
    }
}
