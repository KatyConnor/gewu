package com.gewu.application.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE 连接管理器 - 按会话/用户双索引维护长连接并投递事件。
 * <p>单实例模式（默认）：{@code gewu.sse.distributed=false}，直接本地投递。
 * <p>多副本模式（{@code distributed=true}）：本地投递 + 经 {@link SseBroadcastService}
 * 向 Redis 频道发布，其他实例订阅路由后本地投递（见 SseDistributedConfig）；
 * 本类不感知 Redis 细节，仅持有一个可选的发布器。
 */
@Slf4j
@Component
public class SseEventManager {

    private final ConcurrentHashMap<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    /** 用户 -> 该用户建立的连接（跨其参与的多个会话） */
    private final ConcurrentHashMap<String, List<SseEmitter>> userEmitters = new ConcurrentHashMap<>();
    /** 分布式广播发布器（gewu.sse.distributed=true 时注入） */
    private final ObjectProvider<SseBroadcastService> broadcasterProvider;

    public SseEventManager(ObjectProvider<SseBroadcastService> broadcasterProvider) {
        this.broadcasterProvider = broadcasterProvider;
    }

    public void addEmitter(String sessionId, SseEmitter emitter) {
        addEmitter(sessionId, null, emitter);
    }

    /** 带用户身份注册：连接同时进入会话与用户两个索引 */
    public void addEmitter(String sessionId, String userId, SseEmitter emitter) {
        emitters.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        if (userId != null && !userId.isBlank()) {
            userEmitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        }
        emitter.onCompletion(() -> removeEmitter(sessionId, emitter));
        emitter.onTimeout(() -> removeEmitter(sessionId, emitter));
        emitter.onError(e -> removeEmitter(sessionId, emitter));
    }

    public void removeEmitter(String sessionId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(sessionId);
        if (list == null) return;
        list.remove(emitter);
        if (list.isEmpty()) emitters.remove(sessionId, list);
        // 同步清理用户索引（值为同一 emitter 实例）
        userEmitters.values().forEach(l -> l.remove(emitter));
    }

    /** 会话事件投递入口：本地直发 + 多副本时跨实例发布 */
    public void sendEvent(String sessionId, String eventName, Object data) {
        sendLocalSession(sessionId, eventName, data);
        SseBroadcastService broadcaster = broadcasterProvider.getIfAvailable();
        if (broadcaster != null) {
            broadcaster.broadcastSession(sessionId, eventName, data);
        }
    }

    /** 用户事件投递入口：本地直发 + 多副本时跨实例发布 */
    public void sendToUser(String userId, String eventName, Object data) {
        boolean delivered = sendLocalUser(userId, eventName, data);
        SseBroadcastService broadcaster = broadcasterProvider.getIfAvailable();
        if (broadcaster != null) {
            broadcaster.broadcastUser(userId, eventName, data);
        }
        if (!delivered && broadcaster == null) {
            log.debug("用户无活跃 SSE 连接，推送跳过: userId={}", userId);
        }
    }

    // ==================== 本地投递（订阅器路由也走这里） ====================

    /** 仅本地会话连接投递（多副本订阅器使用） */
    public void sendLocalSession(String sessionId, String eventName, Object data) {
        List<SseEmitter> list = emitters.get(sessionId);
        if (list == null || list.isEmpty()) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                log.warn("推送 SSE 事件失败 sessionId={}, 已移除失效连接", sessionId);
                removeEmitter(sessionId, emitter);
            }
        }
    }

    /** 仅本地用户连接投递（多副本订阅器使用），返回是否有活跃连接 */
    public boolean sendLocalUser(String userId, String eventName, Object data) {
        List<SseEmitter> list = userEmitters.get(userId);
        if (list == null || list.isEmpty()) return false;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                log.warn("推送用户 SSE 事件失败 userId={}, 已移除失效连接", userId);
                list.remove(emitter);
            }
        }
        return true;
    }

    /** 活跃会话连接数（监控/调试用） */
    public int activeSessionCount() {
        return emitters.size();
    }

    /** 活跃用户连接数（监控/调试用） */
    public int activeUserCount() {
        return userEmitters.size();
    }
}
